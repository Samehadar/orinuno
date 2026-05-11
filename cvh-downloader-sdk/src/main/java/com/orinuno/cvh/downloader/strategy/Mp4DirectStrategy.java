package com.orinuno.cvh.downloader.strategy;

import com.orinuno.cvh.downloader.CvhDownloadProgress;
import com.orinuno.cvh.downloader.CvhDownloadRequest;
import com.orinuno.cvh.downloader.CvhDownloaderConfig;
import com.orinuno.cvh.downloader.CvhDownloaderErrorCodes;
import com.orinuno.cvh.downloader.CvhDownloaderException;
import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.candidate.DownloadFormat;
import com.orinuno.cvh.downloader.fs.LocalFsDestination;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Single HTTP GET → file write via {@link java.net.http.HttpClient}. We use the JDK client (not
 * Reactor Netty) because the SDK's signed CDN URLs are ~100 MB single-shot transfers and Reactor
 * Netty's default pipeline stalls reliably on the longer connections.
 *
 * <p>Rejects http:// URLs (CVH signed MP4 is always https) and aborts if the running byte count
 * exceeds {@link CvhDownloaderConfig#maxBytesPerFile()}.
 */
@Slf4j
public final class Mp4DirectStrategy implements DownloadStrategy {

    private static final int CHUNK_SIZE = 64 * 1024;

    private final HttpClient httpClient;
    private final CvhDownloaderConfig config;
    private final LocalFsDestination fs;

    public Mp4DirectStrategy(
            HttpClient httpClient, CvhDownloaderConfig config, LocalFsDestination fs) {
        this.httpClient = httpClient;
        this.config = config;
        this.fs = fs;
    }

    public Mp4DirectStrategy(CvhDownloaderConfig config, LocalFsDestination fs) {
        this(defaultClient(), config, fs);
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean supports(DownloadCandidate candidate) {
        return candidate.format() == DownloadFormat.MP4_DIRECT;
    }

    @Override
    public Mono<Path> download(
            DownloadCandidate candidate,
            CvhDownloadRequest request,
            Path targetMp4Path,
            CvhDownloadProgress progress) {
        if (!candidate.url().startsWith("https://")) {
            return Mono.error(
                    new CvhDownloaderException(
                            CvhDownloaderErrorCodes.CVH_DL_INVALID_REQUEST,
                            "MP4 candidate must use https:// scheme"));
        }
        return Mono.fromCallable(() -> doDownload(candidate, request, targetMp4Path, progress))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Path doDownload(
            DownloadCandidate candidate,
            CvhDownloadRequest request,
            Path targetMp4Path,
            CvhDownloadProgress progress)
            throws IOException, InterruptedException {
        Files.createDirectories(targetMp4Path.getParent());
        Path tempPath = targetMp4Path.resolveSibling(targetMp4Path.getFileName() + ".part");

        HttpRequest httpReq =
                HttpRequest.newBuilder()
                        .uri(URI.create(candidate.url()))
                        .header("Referer", request.referer())
                        .header("User-Agent", config.userAgent())
                        .timeout(Duration.ofSeconds(config.ffmpegTimeoutSeconds()))
                        .GET()
                        .build();
        HttpResponse<InputStream> response =
                httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new CvhDownloaderException(
                    CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                    "MP4 GET returned HTTP " + response.statusCode());
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declaredLength > 0) {
            progress.totalBytes(declaredLength);
            if (declaredLength > config.maxBytesPerFile()) {
                throw new CvhDownloaderException(
                        CvhDownloaderErrorCodes.CVH_DL_TOO_LARGE,
                        "Content-Length "
                                + declaredLength
                                + " exceeds cap "
                                + config.maxBytesPerFile());
            }
        }

        try (InputStream in = response.body();
                OutputStream out =
                        Files.newOutputStream(
                                tempPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
            byte[] buf = new byte[CHUNK_SIZE];
            long total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                total += read;
                progress.addDownloadedBytes(read);
                if (total > config.maxBytesPerFile()) {
                    fs.deleteIfExists(tempPath);
                    throw new CvhDownloaderException(
                            CvhDownloaderErrorCodes.CVH_DL_TOO_LARGE,
                            "MP4 byte count " + total + " exceeds cap " + config.maxBytesPerFile());
                }
            }
        } catch (IOException ex) {
            fs.deleteIfExists(tempPath);
            throw new CvhDownloaderException(
                    CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                    "MP4 stream read failed: " + ex.getMessage(),
                    ex);
        }
        return fs.commit(tempPath, targetMp4Path);
    }
}
