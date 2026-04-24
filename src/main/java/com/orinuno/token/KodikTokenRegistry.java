package com.orinuno.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orinuno.configuration.OrinunoProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Thread-safe Kodik token registry backed by a single JSON file at {@code
 * orinuno.kodik.token-file}. Mirrors the runtime behaviour of AnimeParsers' token handling: 4-tier
 * storage (stable / unstable / legacy / dead), per-function availability matrix, atomic file
 * persistence on every mutation.
 *
 * <p>Concurrency: a {@link ReentrantReadWriteLock} protects the in-memory model; the file is
 * rewritten under the write lock using a {@code .tmp + ATOMIC_MOVE} pattern.
 */
@Slf4j
@Component
public class KodikTokenRegistry {

    private static final List<KodikTokenTier> LIVE_TIERS =
            List.of(KodikTokenTier.STABLE, KodikTokenTier.UNSTABLE, KodikTokenTier.LEGACY);

    /** Functions a LEGACY-tier token is allowed to serve even without explicit flags. */
    private static final Set<KodikFunction> LEGACY_DEFAULT_FUNCTIONS =
            Set.of(
                    KodikFunction.GET_INFO,
                    KodikFunction.GET_LINK,
                    KodikFunction.GET_M3U8_PLAYLIST_LINK);

    private final OrinunoProperties properties;
    private final ObjectProvider<KodikTokenAutoDiscovery> autoDiscoveryProvider;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private Path tokenFilePath;
    private KodikTokenRegistryFile state = KodikTokenRegistryFile.empty();

    public KodikTokenRegistry(
            OrinunoProperties properties,
            ObjectProvider<KodikTokenAutoDiscovery> autoDiscoveryProvider) {
        this.properties = properties;
        this.autoDiscoveryProvider = autoDiscoveryProvider;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        tokenFilePath = Paths.get(properties.getKodik().getTokenFile()).toAbsolutePath();
        log.info("Kodik token registry path: {}", tokenFilePath);

        if (Files.isRegularFile(tokenFilePath)) {
            loadFromFile();
            warnIfWorldReadable();
        } else {
            bootstrapMissingFile();
        }

        int total = totalLive();
        log.info(
                "Kodik token registry loaded: stable={}, unstable={}, legacy={}, dead={} (live={})",
                state.getStable().size(),
                state.getUnstable().size(),
                state.getLegacy().size(),
                state.getDead().size(),
                total);

        if (total == 0) {
            log.warn(
                    "Kodik token registry is empty — Kodik API calls will fail until a token is"
                            + " supplied via data/kodik_tokens.json or KODIK_TOKEN env.");
        }
    }

    // ======================== READ PATH ========================

    /**
     * Resolve the best available token for a given function. Walks {@code STABLE → UNSTABLE →
     * LEGACY} and inside each tier returns the first entry whose {@code functions_availability[fn]}
     * is not explicitly {@code false} (unknown entries are treated optimistically so newly added
     * tokens are tried before the boot validator has a chance to probe them).
     *
     * <p>For tokens in the {@link KodikTokenTier#LEGACY} tier, only the three functions {@link
     * #LEGACY_DEFAULT_FUNCTIONS} are considered even if flags are absent, mirroring AnimeParsers'
     * description of "legacy" tokens.
     *
     * @throws KodikTokenException.NoWorkingTokenException when no suitable token exists.
     */
    public String currentToken(KodikFunction function) {
        lock.readLock().lock();
        try {
            for (KodikTokenTier tier : LIVE_TIERS) {
                for (KodikTokenEntry entry : state.bucket(tier)) {
                    if (isEligible(entry, tier, function)) {
                        return entry.getValue();
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        throw new KodikTokenException.NoWorkingTokenException(
                "No Kodik token available for function " + function.getJsonKey());
    }

    /**
     * Resolve every token that may still serve a function, in the order {@link #currentToken(
     * KodikFunction)} would try them. Useful for the API client's retry-with-next-token logic so it
     * can iterate deterministically.
     */
    public List<String> tokensFor(KodikFunction function) {
        List<String> result = new ArrayList<>();
        lock.readLock().lock();
        try {
            for (KodikTokenTier tier : LIVE_TIERS) {
                for (KodikTokenEntry entry : state.bucket(tier)) {
                    if (isEligible(entry, tier, function)) {
                        result.add(entry.getValue());
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    /** Snapshot of the tier map for diagnostics. Raw values are preserved for internal callers. */
    public Map<KodikTokenTier, List<KodikTokenEntry>> snapshot() {
        lock.readLock().lock();
        try {
            EnumMap<KodikTokenTier, List<KodikTokenEntry>> copy =
                    new EnumMap<>(KodikTokenTier.class);
            for (KodikTokenTier tier : KodikTokenTier.values()) {
                List<KodikTokenEntry> deepCopy = new ArrayList<>();
                for (KodikTokenEntry e : state.bucket(tier)) {
                    deepCopy.add(e.toBuilder().build());
                }
                copy.put(tier, deepCopy);
            }
            return copy;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int countFor(KodikTokenTier tier) {
        lock.readLock().lock();
        try {
            return state.bucket(tier).size();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ======================== WRITE PATH ========================

    /**
     * Register a new token. If it already exists anywhere it is moved to the target tier and the
     * supplied metadata is merged in.
     */
    public void register(KodikTokenEntry entry, KodikTokenTier tier) {
        if (entry == null || entry.getValue() == null || entry.getValue().isBlank()) {
            throw new IllegalArgumentException("Cannot register an entry with a blank value");
        }
        lock.writeLock().lock();
        try {
            KodikTokenEntry existing = findAnyTier(entry.getValue());
            if (existing != null) {
                removeFromAllTiers(existing.getValue());
                if (entry.getFunctionsAvailability() != null
                        && !entry.getFunctionsAvailability().isEmpty()) {
                    existing.setFunctionsAvailability(entry.getFunctionsAvailability());
                }
                if (entry.getLastChecked() != null) {
                    existing.setLastChecked(entry.getLastChecked());
                }
                if (entry.getNote() != null) {
                    existing.setNote(entry.getNote());
                }
                state.bucket(tier).add(existing);
                log.info(
                        "Kodik token {} moved to {} (was present in another tier)",
                        mask(existing.getValue()),
                        tier.getJsonKey());
            } else {
                state.bucket(tier).add(entry);
                log.info(
                        "Kodik token {} registered in {}",
                        mask(entry.getValue()),
                        tier.getJsonKey());
            }
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Mark a {@code (token, function)} pair invalid. Demotes the token if all functions die. */
    public void markInvalid(String tokenValue, KodikFunction function) {
        lock.writeLock().lock();
        try {
            KodikTokenEntry entry = findAnyTier(tokenValue);
            if (entry == null) {
                log.debug(
                        "markInvalid called for unknown token {} function {}",
                        mask(tokenValue),
                        function.getJsonKey());
                return;
            }
            entry.setAvailability(function, false);
            entry.setLastChecked(Instant.now());
            log.warn(
                    "Kodik token {} marked invalid for function {}",
                    mask(entry.getValue()),
                    function.getJsonKey());
            if (entry.isAllDead()) {
                demoteInternal(entry.getValue(), KodikTokenTier.DEAD);
            }
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Record a successful probe for a {@code (token, function)} pair. */
    public void markValid(String tokenValue, KodikFunction function) {
        lock.writeLock().lock();
        try {
            KodikTokenEntry entry = findAnyTier(tokenValue);
            if (entry == null) {
                return;
            }
            entry.setAvailability(function, true);
            entry.setLastChecked(Instant.now());
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Move a token to another tier. No-op if the token is unknown or already in that tier. */
    public void moveToTier(String tokenValue, KodikTokenTier tier) {
        lock.writeLock().lock();
        try {
            demoteInternal(tokenValue, tier);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<KodikTokenEntry> findEntry(String tokenValue) {
        lock.readLock().lock();
        try {
            KodikTokenEntry entry = findAnyTier(tokenValue);
            return entry == null ? Optional.empty() : Optional.of(entry.toBuilder().build());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Reload the file from disk. Mostly for tests and admin flows. */
    public void reload() {
        lock.writeLock().lock();
        try {
            if (Files.isRegularFile(tokenFilePath)) {
                loadFromFile();
            } else {
                state = KodikTokenRegistryFile.empty();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ======================== INTERNAL ========================

    private boolean isEligible(KodikTokenEntry entry, KodikTokenTier tier, KodikFunction function) {
        if (entry == null || entry.getValue() == null || entry.getValue().isBlank()) {
            return false;
        }
        Map<String, Boolean> avail = entry.getFunctionsAvailability();
        if (avail == null || avail.isEmpty()) {
            if (tier == KodikTokenTier.LEGACY) {
                return LEGACY_DEFAULT_FUNCTIONS.contains(function);
            }
            return true;
        }
        Boolean flag = avail.get(function.getJsonKey());
        if (flag == null) {
            if (tier == KodikTokenTier.LEGACY) {
                return LEGACY_DEFAULT_FUNCTIONS.contains(function);
            }
            return true;
        }
        return Boolean.TRUE.equals(flag);
    }

    private KodikTokenEntry findAnyTier(String tokenValue) {
        if (tokenValue == null) {
            return null;
        }
        for (KodikTokenTier tier : KodikTokenTier.values()) {
            for (KodikTokenEntry entry : state.bucket(tier)) {
                if (tokenValue.equals(entry.getValue())) {
                    return entry;
                }
            }
        }
        return null;
    }

    private void removeFromAllTiers(String tokenValue) {
        for (KodikTokenTier tier : KodikTokenTier.values()) {
            state.bucket(tier).removeIf(e -> tokenValue.equals(e.getValue()));
        }
    }

    private void demoteInternal(String tokenValue, KodikTokenTier tier) {
        KodikTokenEntry entry = findAnyTier(tokenValue);
        if (entry == null) {
            return;
        }
        removeFromAllTiers(tokenValue);
        state.bucket(tier).add(entry);
        log.warn("Kodik token {} moved to {}", mask(tokenValue), tier.getJsonKey());
    }

    private int totalLive() {
        return state.getStable().size() + state.getUnstable().size() + state.getLegacy().size();
    }

    private void loadFromFile() {
        try {
            KodikTokenRegistryFile loaded =
                    mapper.readValue(tokenFilePath.toFile(), KodikTokenRegistryFile.class);
            state = normalise(loaded);
        } catch (IOException ex) {
            log.error(
                    "Failed to read Kodik token registry at {} ({}). Starting empty.",
                    tokenFilePath,
                    ex.getMessage());
            state = KodikTokenRegistryFile.empty();
        }
    }

    private KodikTokenRegistryFile normalise(KodikTokenRegistryFile loaded) {
        if (loaded == null) {
            return KodikTokenRegistryFile.empty();
        }
        if (loaded.getStable() == null) {
            loaded.setStable(new ArrayList<>());
        }
        if (loaded.getUnstable() == null) {
            loaded.setUnstable(new ArrayList<>());
        }
        if (loaded.getLegacy() == null) {
            loaded.setLegacy(new ArrayList<>());
        }
        if (loaded.getDead() == null) {
            loaded.setDead(new ArrayList<>());
        }
        return loaded;
    }

    private void bootstrapMissingFile() {
        String envToken = properties.getKodik().getToken();
        if (properties.getKodik().isBootstrapFromEnv() && envToken != null && !envToken.isBlank()) {
            KodikTokenEntry entry =
                    KodikTokenEntry.builder()
                            .value(envToken)
                            .note("seeded from KODIK_TOKEN on first boot")
                            .build();
            state = KodikTokenRegistryFile.empty();
            state.getStable().add(entry);
            persist();
            log.info("Seeded Kodik token registry from KODIK_TOKEN env ({}).", mask(envToken));
            return;
        }

        if (properties.getKodik().isAutoDiscoveryEnabled()) {
            KodikTokenAutoDiscovery discovery = autoDiscoveryProvider.getIfAvailable();
            if (discovery != null) {
                Optional<String> discovered = discovery.discoverLegacyToken();
                if (discovered.isPresent()) {
                    KodikTokenEntry entry =
                            KodikTokenEntry.builder()
                                    .value(discovered.get())
                                    .note("auto-discovered from kodik-add.com on first boot")
                                    .build();
                    state = KodikTokenRegistryFile.empty();
                    state.getLegacy().add(entry);
                    persist();
                    log.warn(
                            "Seeded Kodik token registry via auto-discovery (legacy scope only):"
                                    + " {}",
                            mask(discovered.get()));
                    return;
                }
            }
        }

        log.warn(
                "No Kodik token registry file at {} and no KODIK_TOKEN env provided. Starting"
                        + " empty — API calls will fail until a token is supplied.",
                tokenFilePath);
        state = KodikTokenRegistryFile.empty();
        persist();
    }

    private void persist() {
        try {
            Path parent = tokenFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp =
                    tokenFilePath.resolveSibling(tokenFilePath.getFileName().toString() + ".tmp");
            byte[] payload = mapper.writeValueAsBytes(state);
            Files.write(tmp, payload);
            try {
                Files.move(
                        tmp,
                        tokenFilePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, tokenFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
            tryRestrictPermissions();
        } catch (IOException ex) {
            log.error(
                    "Failed to persist Kodik token registry to {}: {}",
                    tokenFilePath,
                    ex.getMessage());
        }
    }

    private void tryRestrictPermissions() {
        try {
            Files.setPosixFilePermissions(
                    tokenFilePath, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ex) {
            // POSIX not supported (e.g. Windows) — ignore silently.
        }
    }

    private void warnIfWorldReadable() {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tokenFilePath);
            if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn(
                        "Kodik token registry {} is world-readable — consider chmod 600",
                        tokenFilePath);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX perms not supported on this FS — nothing to warn about.
        }
    }

    public static String mask(String token) {
        if (token == null || token.isEmpty()) {
            return "<empty>";
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.length() <= 4) {
            return "****";
        }
        return lower.substring(0, 4) + "…(" + lower.length() + "ch)";
    }
}
