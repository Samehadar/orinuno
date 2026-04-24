package com.orinuno.token;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fallback token discovery that mirrors AnimeParsers' final branch in {@code
 * parser_kodik.py::get_token}: scrape {@code https://kodik-add.com/add-players.min.js?v=2} and
 * extract the legacy token from the embedded initialiser.
 *
 * <p>The returned token is always legacy-scoped (works only for {@code get_info} / {@code get_link}
 * / {@code get_m3u8_playlist_link}).
 */
@Slf4j
@Component
public class KodikTokenAutoDiscovery {

    private static final String SCRIPT_URL = "https://kodik-add.com/add-players.min.js?v=2";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=\"?([A-Za-z0-9]{16,64})");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final AtomicReference<Long> lastRunEpochSecond = new AtomicReference<>(0L);

    public KodikTokenAutoDiscovery(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    /**
     * Attempt to scrape the legacy token from Kodik's public player bootstrap. Returns empty on any
     * failure (network, pattern mismatch).
     */
    public Optional<String> discoverLegacyToken() {
        try {
            String body =
                    webClient
                            .get()
                            .uri(SCRIPT_URL)
                            .header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                            + " (KHTML, like Gecko) Chrome/135.0.0.0"
                                            + " Safari/537.36")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            if (body == null) {
                log.warn("Auto-discovery: empty response from {}", SCRIPT_URL);
                return Optional.empty();
            }
            lastRunEpochSecond.set(System.currentTimeMillis() / 1000L);
            Matcher matcher = TOKEN_PATTERN.matcher(body);
            if (matcher.find()) {
                String token = matcher.group(1);
                log.info(
                        "Auto-discovery extracted token {} from {}",
                        KodikTokenRegistry.mask(token),
                        SCRIPT_URL);
                return Optional.of(token);
            }
            log.warn("Auto-discovery: token pattern not found in {}", SCRIPT_URL);
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Auto-discovery failed against {}: {}", SCRIPT_URL, ex.getMessage());
            return Optional.empty();
        }
    }

    public long getLastRunEpochSecond() {
        return lastRunEpochSecond.get();
    }
}
