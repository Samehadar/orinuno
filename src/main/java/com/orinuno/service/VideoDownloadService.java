package com.orinuno.service;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.repository.EpisodeVariantRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoDownloadService {

    public enum DownloadStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    public record DownloadState(
            DownloadStatus status,
            String filepath,
            String error,
            Integer totalSegments,
            Integer downloadedSegments,
            Long totalBytes) {
        public DownloadState(DownloadStatus status, String filepath, String error) {
            this(status, filepath, error, null, null, null);
        }
    }

    /**
     * Shared mutable progress tracker updated by PlaywrightVideoFetcher during HLS download.
     * Thread-safe via atomic fields.
     */
    public static class DownloadProgress {
        private final AtomicInteger totalSegments = new AtomicInteger(0);
        private final AtomicInteger downloadedSegments = new AtomicInteger(0);
        private final AtomicLong totalBytes = new AtomicLong(0);

        public void setTotalSegments(int total) {
            totalSegments.set(total);
        }

        public void incrementDownloaded() {
            downloadedSegments.incrementAndGet();
        }

        public void addBytes(long bytes) {
            totalBytes.addAndGet(bytes);
        }

        public int getTotalSegments() {
            return totalSegments.get();
        }

        public int getDownloadedSegments() {
            return downloadedSegments.get();
        }

        public long getTotalBytes() {
            return totalBytes.get();
        }

        public DownloadState toState(DownloadStatus status) {
            return new DownloadState(
                    status,
                    null,
                    null,
                    totalSegments.get() > 0 ? totalSegments.get() : null,
                    downloadedSegments.get() > 0 ? downloadedSegments.get() : null,
                    totalBytes.get() > 0 ? totalBytes.get() : null);
        }
    }

    private final EpisodeVariantRepository variantRepository;
    private final KodikVideoDecoderService decoderService;
    private final PlaywrightVideoFetcher playwrightFetcher;
    private final WebClient kodikCdnWebClient;
    private final OrinunoProperties properties;

    private final ConcurrentMap<Long, DownloadState> activeDownloads = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, DownloadProgress> activeProgress = new ConcurrentHashMap<>();

    public DownloadState getDownloadState(Long variantId) {
        DownloadState state = activeDownloads.get(variantId);
        if (state != null) {
            if (state.status() == DownloadStatus.IN_PROGRESS) {
                DownloadProgress progress = activeProgress.get(variantId);
                if (progress != null) {
                    return progress.toState(DownloadStatus.IN_PROGRESS);
                }
            }
            return state;
        }
        return variantRepository
                .findById(variantId)
                .map(
                        v ->
                                v.getLocalFilepath() != null
                                        ? new DownloadState(
                                                DownloadStatus.COMPLETED,
                                                v.getLocalFilepath(),
                                                null)
                                        : new DownloadState(DownloadStatus.PENDING, null, null))
                .orElse(new DownloadState(DownloadStatus.PENDING, null, null));
    }

    /**
     * Starts downloading variant. Returns Mono that completes when download finishes. For
     * fire-and-forget usage, call {@link #startDownloadAsync(Long)} instead.
     */
    public Mono<DownloadState> downloadVariant(Long variantId) {
        DownloadState current = activeDownloads.get(variantId);
        if (current != null && current.status() == DownloadStatus.IN_PROGRESS) {
            DownloadProgress progress = activeProgress.get(variantId);
            return Mono.just(
                    progress != null ? progress.toState(DownloadStatus.IN_PROGRESS) : current);
        }

        return Mono.fromCallable(() -> variantRepository.findById(variantId))
                .flatMap(
                        opt -> {
                            if (opt.isEmpty()) {
                                return Mono.error(
                                        new RuntimeException("Variant not found: " + variantId));
                            }
                            KodikEpisodeVariant variant = opt.get();

                            if (variant.getLocalFilepath() != null
                                    && isNonEmptyFile(Path.of(variant.getLocalFilepath()))) {
                                DownloadState completed =
                                        new DownloadState(
                                                DownloadStatus.COMPLETED,
                                                variant.getLocalFilepath(),
                                                null);
                                return Mono.just(completed);
                            }

                            if (variant.getKodikLink() == null) {
                                return Mono.error(
                                        new RuntimeException(
                                                "No kodik_link for variant " + variantId));
                            }

                            DownloadProgress progress = new DownloadProgress();
                            activeProgress.put(variantId, progress);
                            activeDownloads.put(
                                    variantId,
                                    new DownloadState(DownloadStatus.IN_PROGRESS, null, null));

                            return downloadWithStrategy(variant, progress)
                                    .map(
                                            filePath -> {
                                                variantRepository.updateLocalFilepath(
                                                        variantId, filePath);
                                                DownloadState state =
                                                        new DownloadState(
                                                                DownloadStatus.COMPLETED,
                                                                filePath,
                                                                null,
                                                                progress.getTotalSegments() > 0
                                                                        ? progress
                                                                                .getTotalSegments()
                                                                        : null,
                                                                progress.getDownloadedSegments() > 0
                                                                        ? progress
                                                                                .getDownloadedSegments()
                                                                        : null,
                                                                progress.getTotalBytes() > 0
                                                                        ? progress.getTotalBytes()
                                                                        : null);
                                                activeDownloads.put(variantId, state);
                                                activeProgress.remove(variantId);
                                                log.info(
                                                        "Download completed for variant {}: {}",
                                                        variantId,
                                                        filePath);
                                                return state;
                                            })
                                    .onErrorResume(
                                            e -> {
                                                log.error(
                                                        "Download failed for variant {}: {}",
                                                        variantId,
                                                        e.getMessage(),
                                                        e);
                                                DownloadState failed =
                                                        new DownloadState(
                                                                DownloadStatus.FAILED,
                                                                null,
                                                                e.getMessage());
                                                activeDownloads.put(variantId, failed);
                                                activeProgress.remove(variantId);
                                                return Mono.just(failed);
                                            });
                        });
    }

    /**
     * Starts download in background (fire-and-forget). Returns immediately with IN_PROGRESS. Use
     * {@link #getDownloadState(Long)} to poll for progress.
     */
    public DownloadState startDownloadAsync(Long variantId) {
        DownloadState current = activeDownloads.get(variantId);
        if (current != null && current.status() == DownloadStatus.IN_PROGRESS) {
            DownloadProgress progress = activeProgress.get(variantId);
            return progress != null ? progress.toState(DownloadStatus.IN_PROGRESS) : current;
        }

        var optVariant = variantRepository.findById(variantId);
        if (optVariant.isEmpty()) {
            return new DownloadState(
                    DownloadStatus.FAILED, null, "Variant not found: " + variantId);
        }
        KodikEpisodeVariant variant = optVariant.get();

        if (variant.getLocalFilepath() != null
                && isNonEmptyFile(Path.of(variant.getLocalFilepath()))) {
            return new DownloadState(DownloadStatus.COMPLETED, variant.getLocalFilepath(), null);
        }

        if (variant.getKodikLink() == null) {
            return new DownloadState(
                    DownloadStatus.FAILED, null, "No kodik_link for variant " + variantId);
        }

        DownloadProgress progress = new DownloadProgress();
        activeProgress.put(variantId, progress);
        activeDownloads.put(variantId, new DownloadState(DownloadStatus.IN_PROGRESS, null, null));

        downloadWithStrategy(variant, progress)
                .map(
                        filePath -> {
                            variantRepository.updateLocalFilepath(variantId, filePath);
                            DownloadState state =
                                    new DownloadState(
                                            DownloadStatus.COMPLETED,
                                            filePath,
                                            null,
                                            progress.getTotalSegments() > 0
                                                    ? progress.getTotalSegments()
                                                    : null,
                                            progress.getDownloadedSegments() > 0
                                                    ? progress.getDownloadedSegments()
                                                    : null,
                                            progress.getTotalBytes() > 0
                                                    ? progress.getTotalBytes()
                                                    : null);
                            activeDownloads.put(variantId, state);
                            activeProgress.remove(variantId);
                            log.info("Download completed for variant {}: {}", variantId, filePath);
                            return state;
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Download failed for variant {}: {}",
                                    variantId,
                                    e.getMessage(),
                                    e);
                            DownloadState failed =
                                    new DownloadState(DownloadStatus.FAILED, null, e.getMessage());
                            activeDownloads.put(variantId, failed);
                            activeProgress.remove(variantId);
                            return Mono.just(failed);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return new DownloadState(DownloadStatus.IN_PROGRESS, null, null);
    }

    public Mono<Integer> downloadAllForContent(Long contentId) {
        return Mono.fromCallable(() -> variantRepository.findByContentId(contentId))
                .flatMap(
                        variants -> {
                            var needsDownload =
                                    variants.stream()
                                            .filter(
                                                    v ->
                                                            v.getLocalFilepath() == null
                                                                    && v.getKodikLink() != null)
                                            .toList();

                            if (needsDownload.isEmpty()) {
                                return Mono.just(0);
                            }

                            return Flux.fromIterable(needsDownload)
                                    .concatMap(
                                            v ->
                                                    downloadVariant(v.getId())
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic()))
                                    .filter(s -> s.status() == DownloadStatus.COMPLETED)
                                    .count()
                                    .map(Long::intValue);
                        });
    }

    private Mono<String> downloadWithStrategy(
            KodikEpisodeVariant variant, DownloadProgress progress) {
        Path targetPath = buildFilePath(variant);

        if (playwrightFetcher.isAvailable()) {
            log.info("Downloading variant {} via Playwright (headless browser)", variant.getId());
            return playwrightFetcher
                    .downloadVideo(variant.getKodikLink(), targetPath, progress)
                    .onErrorResume(
                            e -> {
                                log.warn(
                                        "Playwright download failed for variant {}, falling back to"
                                                + " WebClient: {}",
                                        variant.getId(),
                                        e.getMessage());
                                return downloadViaWebClient(variant, targetPath);
                            });
        }

        log.info("Playwright not available, downloading variant {} via WebClient", variant.getId());
        return downloadViaWebClient(variant, targetPath);
    }

    private Mono<String> downloadViaWebClient(KodikEpisodeVariant variant, Path targetPath) {
        return decoderService
                .decode(variant.getKodikLink())
                .doOnNext(
                        links ->
                                log.info(
                                        "Decoded {} qualities for variant {}: {}",
                                        links.size(),
                                        variant.getId(),
                                        links.keySet()))
                .map(this::pickBestQualityUrl)
                .doOnNext(
                        url ->
                                log.info(
                                        "Downloading from CDN for variant {}: {}...",
                                        variant.getId(),
                                        url.substring(0, Math.min(80, url.length()))))
                .flatMap(cdnUrl -> downloadFromCdn(cdnUrl, variant));
    }

    private String pickBestQualityUrl(Map<String, String> videoLinks) {
        return videoLinks.entrySet().stream()
                .max(Comparator.comparingInt(e -> parseQuality(e.getKey())))
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new RuntimeException("No video URLs decoded"));
    }

    private int parseQuality(String key) {
        try {
            return Integer.parseInt(key.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Mono<String> downloadFromCdn(String cdnUrl, KodikEpisodeVariant variant) {
        Path targetPath = buildFilePath(variant);
        return fetchWithRedirects(cdnUrl, 5)
                .publishOn(Schedulers.boundedElastic())
                .collectList()
                .flatMap(
                        buffers ->
                                Mono.fromCallable(
                                        () -> {
                                            Files.createDirectories(targetPath.getParent());
                                            AtomicLong totalBytes = new AtomicLong(0);

                                            try (OutputStream os =
                                                    Files.newOutputStream(
                                                            targetPath,
                                                            StandardOpenOption.CREATE,
                                                            StandardOpenOption.WRITE,
                                                            StandardOpenOption.TRUNCATE_EXISTING)) {
                                                for (DataBuffer buffer : buffers) {
                                                    byte[] bytes =
                                                            new byte[buffer.readableByteCount()];
                                                    buffer.read(bytes);
                                                    os.write(bytes);
                                                    totalBytes.addAndGet(bytes.length);
                                                    DataBufferUtils.release(buffer);
                                                }
                                            }

                                            long size = totalBytes.get();
                                            log.info(
                                                    "Written {} bytes ({} MB) to {}",
                                                    size,
                                                    size / (1024 * 1024),
                                                    targetPath);

                                            if (size == 0) {
                                                Files.deleteIfExists(targetPath);
                                                throw new RuntimeException(
                                                        "Downloaded file is empty (0 bytes) for"
                                                                + " variant "
                                                                + variant.getId());
                                            }

                                            return targetPath.toAbsolutePath().toString();
                                        }));
    }

    private Flux<DataBuffer> fetchWithRedirects(String url, int maxRedirects) {
        if (maxRedirects <= 0) {
            return Flux.error(new RuntimeException("Too many redirects"));
        }

        return kodikCdnWebClient
                .get()
                .uri(url)
                .exchangeToFlux(
                        response -> {
                            int status = response.statusCode().value();
                            log.info(
                                    "CDN {} -> status={}, content-length={}",
                                    url.substring(0, Math.min(60, url.length())),
                                    status,
                                    response.headers().contentLength().orElse(-1));

                            if (status >= 300 && status < 400) {
                                String location =
                                        response.headers().asHttpHeaders().getFirst("Location");
                                response.releaseBody().subscribe();
                                if (location == null) {
                                    return Flux.error(
                                            new RuntimeException("Redirect without Location"));
                                }
                                if (location.startsWith("/")) {
                                    try {
                                        java.net.URI base = java.net.URI.create(url);
                                        location =
                                                base.getScheme()
                                                        + "://"
                                                        + base.getHost()
                                                        + location;
                                    } catch (Exception ignored) {
                                    }
                                }
                                log.info(
                                        "Following redirect -> {}...",
                                        location.substring(0, Math.min(80, location.length())));
                                return fetchWithRedirects(location, maxRedirects - 1);
                            }

                            if (!response.statusCode().is2xxSuccessful()) {
                                response.releaseBody().subscribe();
                                return Flux.error(new RuntimeException("CDN returned " + status));
                            }

                            return response.bodyToFlux(DataBuffer.class);
                        });
    }

    private static boolean isNonEmptyFile(Path path) {
        try {
            return Files.exists(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private Path buildFilePath(KodikEpisodeVariant variant) {
        String basePath = properties.getStorage().getBasePath();
        String filename =
                String.format(
                        "c%d_s%d_e%d_t%d.mp4",
                        variant.getContentId(),
                        variant.getSeasonNumber() != null ? variant.getSeasonNumber() : 0,
                        variant.getEpisodeNumber() != null ? variant.getEpisodeNumber() : 0,
                        variant.getTranslationId() != null ? variant.getTranslationId() : 0);
        return Path.of(basePath, String.valueOf(variant.getContentId()), filename);
    }
}
