package com.orinuno.cvh.downloader.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.downloader.CvhDownloadProgress;
import com.orinuno.cvh.downloader.CvhDownloadRequest;
import com.orinuno.cvh.downloader.CvhDownloaderConfig;
import com.orinuno.cvh.downloader.CvhDownloaderErrorCodes;
import com.orinuno.cvh.downloader.CvhDownloaderException;
import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.candidate.Mp4Quality;
import com.orinuno.cvh.downloader.fs.LocalFsDestination;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

/**
 * Tests for the parallel range-chunked path in {@link Mp4DirectStrategy}. Uses a {@link
 * MockWebServer} that understands {@code HEAD} + {@code Range} so the strategy can be exercised
 * end-to-end without hitting a real CDN.
 */
class Mp4DirectStrategyParallelTest {

    private MockWebServer server;
    private byte[] payload;
    private boolean serveAcceptRanges = true;
    private final AtomicInteger headHits = new AtomicInteger();
    private final AtomicInteger rangeHits = new AtomicInteger();
    private final AtomicInteger fullGetHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        payload = new byte[10 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xff);
        }
        server = new MockWebServer();
        server.setDispatcher(new RangeAwareDispatcher());
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private Mp4DirectStrategy buildStrategy(CvhDownloaderConfig config, LocalFsDestination fs) {
        java.net.http.HttpClient base = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpClient rewriting = new RewritingHttpClient(base, server);
        return new Mp4DirectStrategy(rewriting, config, fs);
    }

    @Test
    void splitRangesProducesNonOverlappingCoverage() {
        List<long[]> ranges = Mp4DirectStrategy.splitRanges(100, 4);
        assertThat(ranges).hasSize(4);
        long covered = 0;
        long prevEnd = -1;
        for (long[] r : ranges) {
            assertThat(r[0]).isEqualTo(prevEnd + 1);
            assertThat(r[1]).isGreaterThanOrEqualTo(r[0]);
            covered += r[1] - r[0] + 1;
            prevEnd = r[1];
        }
        assertThat(covered).isEqualTo(100);
        assertThat(prevEnd).isEqualTo(99);
    }

    @Test
    void parallelChunksAssembleCompleteFile(@TempDir Path baseDir) throws IOException {
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder()
                        .outputBaseDir(baseDir)
                        .mp4ParallelChunks(4)
                        .mp4MinChunkBytes(64 * 1024)
                        .build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = buildStrategy(config, fs);
        DownloadCandidate candidate =
                DownloadCandidate.mp4("https://test.local/v.mp4", Mp4Quality.P720);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("parallel").referer("https://r/").build();
        Path target = fs.resolveSafe("parallel.mp4");
        CvhDownloadProgress progress = new CvhDownloadProgress();

        StepVerifier.create(strategy.download(candidate, request, target, progress))
                .assertNext(path -> assertThat(path).isEqualTo(target))
                .verifyComplete();

        // Order matters: headHits first tells us if HEAD probe even reached the mock; rangeHits
        // confirms the parallel path took it; progress counters are last (set inside the path).
        assertThat(headHits.get()).as("HEAD probe").isEqualTo(1);
        assertThat(rangeHits.get()).as("range GETs").isEqualTo(4);
        assertThat(fullGetHits.get()).as("non-range GETs").isZero();
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.size(target)).isEqualTo(payload.length);
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
        assertThat(progress.downloadedBytes()).isEqualTo(payload.length);
        assertThat(progress.totalBytes()).isEqualTo(payload.length);
        assertThat(progress.totalSegments()).isEqualTo(4);
        assertThat(progress.downloadedSegments()).isEqualTo(4);
    }

    @Test
    void fallsBackToSingleStreamWhenAcceptRangesAbsent(@TempDir Path baseDir) throws IOException {
        serveAcceptRanges = false;
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder()
                        .outputBaseDir(baseDir)
                        .mp4ParallelChunks(4)
                        .mp4MinChunkBytes(64 * 1024)
                        .build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = buildStrategy(config, fs);
        DownloadCandidate candidate =
                DownloadCandidate.mp4("https://test.local/v.mp4", Mp4Quality.P720);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("plain").referer("https://r/").build();
        Path target = fs.resolveSafe("plain.mp4");

        StepVerifier.create(
                        strategy.download(candidate, request, target, new CvhDownloadProgress()))
                .assertNext(path -> assertThat(path).isEqualTo(target))
                .verifyComplete();

        assertThat(Files.size(target)).isEqualTo(payload.length);
        assertThat(rangeHits.get()).isZero();
        assertThat(fullGetHits.get()).isEqualTo(1);
    }

    @Test
    void fallsBackToSingleStreamWhenFileTooSmall(@TempDir Path baseDir) throws IOException {
        payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder()
                        .outputBaseDir(baseDir)
                        .mp4ParallelChunks(4)
                        .mp4MinChunkBytes(64 * 1024)
                        .build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = buildStrategy(config, fs);
        DownloadCandidate candidate =
                DownloadCandidate.mp4("https://test.local/v.mp4", Mp4Quality.P720);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("tiny").referer("https://r/").build();

        StepVerifier.create(
                        strategy.download(
                                candidate,
                                request,
                                fs.resolveSafe("tiny.mp4"),
                                new CvhDownloadProgress()))
                .assertNext(path -> assertThat(Files.exists(path)).isTrue())
                .verifyComplete();
        assertThat(rangeHits.get()).isZero();
        assertThat(fullGetHits.get()).isEqualTo(1);
    }

    @Test
    void headContentLengthAboveCapFailsFast(@TempDir Path baseDir) {
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder()
                        .outputBaseDir(baseDir)
                        .maxBytesPerFile(1024)
                        .mp4MinChunkBytes(64)
                        .build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = buildStrategy(config, fs);
        DownloadCandidate candidate =
                DownloadCandidate.mp4("https://test.local/v.mp4", Mp4Quality.P720);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("big").referer("https://r/").build();

        StepVerifier.create(
                        strategy.download(
                                candidate,
                                request,
                                fs.resolveSafe("big.mp4"),
                                new CvhDownloadProgress()))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((CvhDownloaderException) ex).errorCode())
                                        .isEqualTo(CvhDownloaderErrorCodes.CVH_DL_TOO_LARGE))
                .verify();
    }

    @Test
    void parallelDisabledForcesSingleStream(@TempDir Path baseDir) throws IOException {
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder()
                        .outputBaseDir(baseDir)
                        .mp4ParallelEnabled(false)
                        .build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = buildStrategy(config, fs);
        DownloadCandidate candidate =
                DownloadCandidate.mp4("https://test.local/v.mp4", Mp4Quality.P720);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("nopar").referer("https://r/").build();

        StepVerifier.create(
                        strategy.download(
                                candidate,
                                request,
                                fs.resolveSafe("nopar.mp4"),
                                new CvhDownloadProgress()))
                .assertNext(path -> assertThat(Files.exists(path)).isTrue())
                .verifyComplete();
        assertThat(headHits.get()).isZero();
        assertThat(rangeHits.get()).isZero();
        assertThat(fullGetHits.get()).isEqualTo(1);
    }

    /** MockWebServer dispatcher that honors HEAD + Range so the parallel path can be exercised. */
    private final class RangeAwareDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            if ("HEAD".equalsIgnoreCase(request.getMethod())) {
                headHits.incrementAndGet();
                // setHeader replaces the auto-injected Content-Length: 0 that MockWebServer adds
                // for empty bodies; addHeader would leave the auto value first in the list and
                // the client picks that one.
                MockResponse r =
                        new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Length", String.valueOf(payload.length));
                if (serveAcceptRanges) {
                    r.addHeader("Accept-Ranges", "bytes");
                }
                return r;
            }
            String range = request.getHeader("Range");
            if (range != null && range.startsWith("bytes=")) {
                rangeHits.incrementAndGet();
                String[] parts = range.substring("bytes=".length()).split("-");
                int start = Integer.parseInt(parts[0]);
                int end = Integer.parseInt(parts[1]);
                byte[] slice = new byte[end - start + 1];
                System.arraycopy(payload, start, slice, 0, slice.length);
                return new MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Length", slice.length)
                        .addHeader(
                                "Content-Range",
                                "bytes " + start + "-" + end + "/" + payload.length)
                        .setBody(new Buffer().write(slice));
            }
            fullGetHits.incrementAndGet();
            return new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Length", payload.length)
                    .setBody(new Buffer().write(payload));
        }
    }

    // -------------------------- http client redirecting to MockWebServer

    /**
     * Wraps {@link java.net.http.HttpClient} so every send rewrites the URL to point at the local
     * MockWebServer. Keeps {@link Mp4DirectStrategy} unchanged.
     */
    private static final class RewritingHttpClient extends java.net.http.HttpClient {
        private final java.net.http.HttpClient base;
        private final MockWebServer server;

        RewritingHttpClient(java.net.http.HttpClient base, MockWebServer server) {
            this.base = base;
            this.server = server;
        }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return base.cookieHandler();
        }

        @Override
        public java.util.Optional<java.time.Duration> connectTimeout() {
            return base.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return base.followRedirects();
        }

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() {
            return base.proxy();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return base.sslContext();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return base.sslParameters();
        }

        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() {
            return base.authenticator();
        }

        @Override
        public Version version() {
            return base.version();
        }

        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() {
            return base.executor();
        }

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            return base.send(rewrite(request), responseBodyHandler);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
            return base.sendAsync(rewrite(request), responseBodyHandler);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
                java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return base.sendAsync(rewrite(request), responseBodyHandler, pushPromiseHandler);
        }

        private java.net.http.HttpRequest rewrite(java.net.http.HttpRequest original) {
            java.net.URI dest = server.url(original.uri().getPath()).uri();
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(dest);
            original.headers()
                    .map()
                    .forEach(
                            (k, vs) ->
                                    vs.forEach(
                                            v -> {
                                                try {
                                                    b.header(k, v);
                                                } catch (IllegalArgumentException ignored) {
                                                    // restricted header set automatically
                                                }
                                            }));
            switch (original.method()) {
                case "HEAD" -> b.method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody());
                case "GET" -> b.GET();
                default ->
                        b.method(
                                original.method(),
                                original.bodyPublisher()
                                        .orElse(java.net.http.HttpRequest.BodyPublishers.noBody()));
            }
            original.timeout().ifPresent(b::timeout);
            return b.build();
        }
    }
}
