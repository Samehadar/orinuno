package com.orinuno.cvh.downloader.segment;

import com.orinuno.cvh.downloader.CvhDownloadProgress;
import com.orinuno.cvh.downloader.CvhDownloaderConfig;
import com.orinuno.cvh.downloader.CvhDownloaderErrorCodes;
import com.orinuno.cvh.downloader.CvhDownloaderException;
import com.orinuno.cvh.downloader.hls.HlsRetryPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Downloads an ordered list of HLS segment URLs in parallel, writes each to its own file inside a
 * working directory, and resolves the result back in original playback order. Uses {@link
 * HlsRetryPolicy} for retriable-status decisions and a linear backoff matching the policy.
 *
 * <p>Honors the SDK's {@code maxBytesPerFile} cap by summing segment sizes as they land — exceeding
 * the cap aborts the in-flight Flux with {@link CvhDownloaderErrorCodes#CVH_DL_TOO_LARGE}.
 */
@Slf4j
public final class ParallelSegmentDownloader {

    private final WebClient httpClient;
    private final CvhDownloaderConfig config;

    public ParallelSegmentDownloader(WebClient httpClient, CvhDownloaderConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    public Mono<List<Path>> downloadAll(
            List<String> segmentUrls, Path workDir, String referer, CvhDownloadProgress progress) {
        if (segmentUrls == null || segmentUrls.isEmpty()) {
            return Mono.error(
                    new CvhDownloaderException(
                            CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                            "Empty segment list — nothing to download"));
        }
        progress.totalSegments(segmentUrls.size());
        List<IndexedUrl> indexed = new ArrayList<>(segmentUrls.size());
        for (int i = 0; i < segmentUrls.size(); i++) {
            indexed.add(new IndexedUrl(i, segmentUrls.get(i)));
        }

        Path[] outputs = new Path[segmentUrls.size()];
        return Flux.fromIterable(indexed)
                .flatMap(
                        item ->
                                fetchSegment(item, workDir, referer, progress)
                                        .map(
                                                p -> {
                                                    outputs[item.index] = p;
                                                    return p;
                                                }),
                        Math.max(1, config.segmentConcurrency()))
                .then(Mono.fromSupplier(() -> List.of(outputs)));
    }

    private Mono<Path> fetchSegment(
            IndexedUrl item, Path workDir, String referer, CvhDownloadProgress progress) {
        return Mono.defer(
                        () -> {
                            Path target = workDir.resolve(String.format("seg-%05d.ts", item.index));
                            return httpClient
                                    .get()
                                    .uri(item.url)
                                    .header("Referer", referer)
                                    .retrieve()
                                    .bodyToFlux(DataBuffer.class)
                                    .as(buf -> DataBufferUtils.write(buf, target))
                                    .then(
                                            Mono.fromCallable(
                                                    () -> {
                                                        long size = Files.size(target);
                                                        long total =
                                                                progress.downloadedBytes() + size;
                                                        if (total > config.maxBytesPerFile()) {
                                                            throw new CvhDownloaderException(
                                                                    CvhDownloaderErrorCodes
                                                                            .CVH_DL_TOO_LARGE,
                                                                    "Aggregated segment size "
                                                                            + total
                                                                            + " exceeds cap "
                                                                            + config
                                                                                    .maxBytesPerFile());
                                                        }
                                                        progress.addDownloadedBytes(size);
                                                        progress.incrementDownloadedSegments();
                                                        return target;
                                                    }));
                        })
                .retryWhen(
                        Retry.backoff(
                                        Math.max(0, config.segmentRetryMaxAttempts() - 1),
                                        Duration.ofMillis(config.segmentRetryBaseDelayMs()))
                                .filter(ParallelSegmentDownloader::isRetriable));
    }

    private static boolean isRetriable(Throwable ex) {
        if (ex instanceof CvhDownloaderException) {
            return false;
        }
        if (ex instanceof WebClientResponseException wcre) {
            return HlsRetryPolicy.isRetriableStatus(wcre.getStatusCode().value());
        }
        return ex instanceof IOException;
    }

    private record IndexedUrl(int index, String url) {}
}
