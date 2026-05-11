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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Direct MP4 downloader. Uses {@link java.net.http.HttpClient} (not Reactor Netty — Netty stalls on
 * 100 MB single-shot transfers from the CVH CDN).
 *
 * <p>Two paths:
 *
 * <ul>
 *   <li><b>Parallel</b> — when the server advertises {@code Accept-Ranges: bytes} via HEAD and the
 *       file is large enough, the download is split into {@link
 *       CvhDownloaderConfig#mp4ParallelChunks()} byte-range slices that fetch concurrently.
 *       Empirically CVH ({@code ok6-1.vkuser.net}) throttles a single TCP stream at ~1 MB/s but
 *       happily serves multiple parallel ranges, giving ~Nx throughput.
 *   <li><b>Single-stream fallback</b> — when HEAD fails, {@code Accept-Ranges} is missing, the file
 *       is smaller than {@code mp4MinChunkBytes} times {@code mp4ParallelChunks}, or {@link
 *       CvhDownloaderConfig#mp4ParallelEnabled()} is false. Streams the full body straight to a
 *       single {@link OutputStream}.
 * </ul>
 *
 * <p>Both paths enforce the {@code maxBytesPerFile} cap (HEAD cap when known, mid-stream cap
 * otherwise) and reject http:// candidates.
 */
@Slf4j
public final class Mp4DirectStrategy implements DownloadStrategy {

    private static final int CHUNK_BUF = 64 * 1024;

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
        return Mono.fromCallable(() -> probe(candidate, request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        probe -> {
                            if (probe.canParallel(config)) {
                                return parallelDownload(
                                        candidate, request, probe, targetMp4Path, progress);
                            }
                            return Mono.fromCallable(
                                            () ->
                                                    singleStream(
                                                            candidate,
                                                            request,
                                                            targetMp4Path,
                                                            progress))
                                    .subscribeOn(Schedulers.boundedElastic());
                        });
    }

    // ------------------------------------------------------------------ HEAD probe

    private HeadProbe probe(DownloadCandidate candidate, CvhDownloadRequest request) {
        if (!config.mp4ParallelEnabled()) {
            return HeadProbe.unsupported();
        }
        try {
            HttpRequest head =
                    HttpRequest.newBuilder()
                            .uri(URI.create(candidate.url()))
                            .header("Referer", request.referer())
                            .header("User-Agent", config.userAgent())
                            .timeout(Duration.ofSeconds(20))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build();
            HttpResponse<Void> response =
                    httpClient.send(head, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return HeadProbe.unsupported();
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            boolean acceptsRanges =
                    response.headers()
                            .firstValue("Accept-Ranges")
                            .map(v -> v.toLowerCase().contains("bytes"))
                            .orElse(false);
            if (contentLength > config.maxBytesPerFile()) {
                throw new CvhDownloaderException(
                        CvhDownloaderErrorCodes.CVH_DL_TOO_LARGE,
                        "Content-Length "
                                + contentLength
                                + " exceeds cap "
                                + config.maxBytesPerFile());
            }
            return new HeadProbe(contentLength, acceptsRanges);
        } catch (CvhDownloaderException ex) {
            throw ex;
        } catch (Exception ex) {
            // Any HEAD failure (timeout, 405, etc.) just disables the parallel path; we still try
            // a single-stream GET below.
            log.debug("HEAD probe failed for MP4, will single-stream: {}", ex.toString());
            return HeadProbe.unsupported();
        }
    }

    // ------------------------------------------------------------------ parallel path

    private Mono<Path> parallelDownload(
            DownloadCandidate candidate,
            CvhDownloadRequest request,
            HeadProbe probe,
            Path targetMp4Path,
            CvhDownloadProgress progress) {
        long total = probe.contentLength();
        int parts = config.mp4ParallelChunks();
        List<long[]> ranges = splitRanges(total, parts);
        progress.totalBytes(total);
        progress.totalSegments(ranges.size());

        Path tempPath = targetMp4Path.resolveSibling(targetMp4Path.getFileName() + ".part");
        return Mono.fromCallable(
                        () -> {
                            Files.createDirectories(targetMp4Path.getParent());
                            preallocate(tempPath, total);
                            return tempPath;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        tmp ->
                                Flux.fromIterable(ranges)
                                        .flatMap(
                                                range ->
                                                        Mono.fromCallable(
                                                                        () ->
                                                                                fetchRange(
                                                                                        candidate,
                                                                                        request,
                                                                                        tmp,
                                                                                        range[0],
                                                                                        range[1],
                                                                                        progress))
                                                                .subscribeOn(
                                                                        Schedulers
                                                                                .boundedElastic()),
                                                Math.max(1, parts))
                                        .then(
                                                Mono.fromCallable(
                                                                () -> {
                                                                    long written = Files.size(tmp);
                                                                    if (written != total) {
                                                                        fs.deleteIfExists(tmp);
                                                                        throw new CvhDownloaderException(
                                                                                CvhDownloaderErrorCodes
                                                                                        .CVH_DL_NETWORK,
                                                                                "Parallel download"
                                                                                        + " wrote "
                                                                                        + written
                                                                                        + " bytes"
                                                                                        + " but expected"
                                                                                        + " "
                                                                                        + total);
                                                                    }
                                                                    return fs.commit(
                                                                            tmp, targetMp4Path);
                                                                })
                                                        .subscribeOn(Schedulers.boundedElastic())))
                .doOnError(ex -> fs.deleteIfExists(tempPath))
                .onErrorMap(this::mapError);
    }

    static List<long[]> splitRanges(long totalBytes, int parts) {
        if (parts < 1) {
            parts = 1;
        }
        long chunk = totalBytes / parts;
        long remainder = totalBytes - chunk * parts;
        List<long[]> out = new ArrayList<>(parts);
        long offset = 0;
        for (int i = 0; i < parts; i++) {
            long size = chunk + (i < remainder ? 1 : 0);
            if (size <= 0) {
                continue;
            }
            long end = offset + size - 1;
            out.add(new long[] {offset, end});
            offset += size;
        }
        return out;
    }

    private static void preallocate(Path target, long size) throws IOException {
        try (FileChannel ch =
                FileChannel.open(
                        target,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            if (size > 0) {
                ch.position(size - 1);
                ch.write(ByteBuffer.wrap(new byte[] {0}));
            }
        }
    }

    private Void fetchRange(
            DownloadCandidate candidate,
            CvhDownloadRequest request,
            Path tmp,
            long startInclusive,
            long endInclusive,
            CvhDownloadProgress progress)
            throws IOException, InterruptedException {
        long size = endInclusive - startInclusive + 1;
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(candidate.url()))
                        .header("Referer", request.referer())
                        .header("User-Agent", config.userAgent())
                        .header("Range", "bytes=" + startInclusive + "-" + endInclusive)
                        .timeout(Duration.ofSeconds(config.ffmpegTimeoutSeconds()))
                        .GET()
                        .build();
        HttpResponse<InputStream> response =
                httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 206 && response.statusCode() != 200) {
            throw new CvhDownloaderException(
                    CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                    "Range GET returned HTTP " + response.statusCode());
        }
        try (InputStream in = response.body();
                FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.position(startInclusive);
            byte[] buf = new byte[CHUNK_BUF];
            long written = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                ch.write(ByteBuffer.wrap(buf, 0, read));
                written += read;
                progress.addDownloadedBytes(read);
            }
            if (written != size) {
                throw new CvhDownloaderException(
                        CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                        "Range chunk wrote "
                                + written
                                + " bytes but expected "
                                + size
                                + " (range "
                                + startInclusive
                                + "-"
                                + endInclusive
                                + ")");
            }
        }
        progress.incrementDownloadedSegments();
        return null;
    }

    // ------------------------------------------------------------------ single-stream fallback

    private Path singleStream(
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
            byte[] buf = new byte[CHUNK_BUF];
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
        } catch (CvhDownloaderException ex) {
            fs.deleteIfExists(tempPath);
            throw ex;
        } catch (IOException ex) {
            fs.deleteIfExists(tempPath);
            throw new CvhDownloaderException(
                    CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                    "MP4 stream read failed: " + ex.getMessage(),
                    ex);
        }
        return fs.commit(tempPath, targetMp4Path);
    }

    private Throwable mapError(Throwable ex) {
        if (ex instanceof CvhDownloaderException) {
            return ex;
        }
        return new CvhDownloaderException(
                CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                "MP4 parallel download failed: " + ex.getMessage(),
                ex);
    }

    /** HEAD-probe outcome controlling which download path the strategy takes. */
    record HeadProbe(long contentLength, boolean acceptsRanges) {

        static HeadProbe unsupported() {
            return new HeadProbe(-1, false);
        }

        boolean canParallel(CvhDownloaderConfig config) {
            if (!config.mp4ParallelEnabled() || !acceptsRanges || contentLength <= 0) {
                return false;
            }
            long minTotal = (long) config.mp4ParallelChunks() * config.mp4MinChunkBytes();
            return contentLength >= minTotal;
        }
    }
}
