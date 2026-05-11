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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

/**
 * MockWebServer-backed tests. The strategy enforces https:// for production candidates, but we can
 * bypass that guard by sending a candidate that already starts with https and pointing our
 * HttpClient mock through a custom client — instead we keep the simpler approach: validate the
 * https guard plus the byte-cap on a small in-memory inner HttpClient that talks to a local
 * MockWebServer over plain http (test-only HttpClient bypasses scheme check by mutating the
 * candidate URL... see below).
 */
class Mp4DirectStrategyTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void rejectsNonHttpsCandidate(@TempDir Path baseDir) {
        CvhDownloaderConfig config = CvhDownloaderConfig.builder().outputBaseDir(baseDir).build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = new Mp4DirectStrategy(config, fs);
        DownloadCandidate candidate =
                DownloadCandidate.mp4("http://insecure.test/v.mp4", Mp4Quality.P720);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("x").referer("https://r/").build();

        StepVerifier.create(
                        strategy.download(
                                candidate,
                                request,
                                baseDir.resolve("x.mp4"),
                                new CvhDownloadProgress()))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((CvhDownloaderException) ex).errorCode())
                                        .isEqualTo(CvhDownloaderErrorCodes.CVH_DL_INVALID_REQUEST))
                .verify();
    }

    @Test
    void runningByteCountAboveCapAbortsDownload(@TempDir Path baseDir) throws Exception {
        byte[] body = "x".repeat(256).getBytes(StandardCharsets.ISO_8859_1);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(body)));
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder().outputBaseDir(baseDir).maxBytesPerFile(10).build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = strategyAgainstMock(config, fs);
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
    void writesPayloadAndCommitsToFinalPath(@TempDir Path baseDir) throws Exception {
        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0x7f);
        }
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Length", String.valueOf(payload.length))
                        .setBody(new Buffer().write(payload)));
        CvhDownloaderConfig config = CvhDownloaderConfig.builder().outputBaseDir(baseDir).build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Mp4DirectStrategy strategy = strategyAgainstMock(config, fs);
        DownloadCandidate candidate =
                DownloadCandidate.mp4("https://test.local/v.mp4", Mp4Quality.P720);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("test").referer("https://r/").build();
        Path target = fs.resolveSafe("test.mp4");
        CvhDownloadProgress progress = new CvhDownloadProgress();

        StepVerifier.create(strategy.download(candidate, request, target, progress))
                .assertNext(path -> assertThat(path).isEqualTo(target))
                .verifyComplete();
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.size(target)).isEqualTo(payload.length);
        assertThat(progress.downloadedBytes()).isEqualTo(payload.length);
        assertThat(progress.totalBytes()).isEqualTo(payload.length);
    }

    /**
     * Builds a strategy whose HttpClient rewrites every request to point at the local
     * MockWebServer. Keeps the strategy under test unmodified (still demands https:// on its public
     * API).
     */
    private Mp4DirectStrategy strategyAgainstMock(
            CvhDownloaderConfig config, LocalFsDestination fs) {
        HttpClient base = HttpClient.newHttpClient();
        HttpClient rewriting =
                new HttpClient() {
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
                    public <T> HttpResponse<T> send(
                            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                            throws IOException, InterruptedException {
                        HttpRequest rewritten = redirectToMock(request);
                        return base.send(rewritten, responseBodyHandler);
                    }

                    @Override
                    public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
                        return base.sendAsync(redirectToMock(request), responseBodyHandler);
                    }

                    @Override
                    public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                            HttpRequest request,
                            HttpResponse.BodyHandler<T> responseBodyHandler,
                            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
                        return base.sendAsync(
                                redirectToMock(request), responseBodyHandler, pushPromiseHandler);
                    }
                };
        return new Mp4DirectStrategy(rewriting, config, fs);
    }

    private HttpRequest redirectToMock(HttpRequest original) {
        URI dest = server.url(original.uri().getPath()).uri();
        HttpRequest.Builder b = HttpRequest.newBuilder(dest);
        original.headers().map().forEach((k, vs) -> vs.forEach(v -> safeHeader(b, k, v)));
        original.method();
        return b.GET().build();
    }

    private static void safeHeader(HttpRequest.Builder b, String k, String v) {
        try {
            b.header(k, v);
        } catch (IllegalArgumentException ignored) {
            // skip restricted headers like Host/Content-Length set automatically by HttpClient
        }
    }
}
