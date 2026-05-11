package com.orinuno.cvh.downloader.strategy;

import com.orinuno.cvh.downloader.CvhDownloadProgress;
import com.orinuno.cvh.downloader.CvhDownloadRequest;
import com.orinuno.cvh.downloader.CvhDownloaderConfig;
import com.orinuno.cvh.downloader.CvhDownloaderErrorCodes;
import com.orinuno.cvh.downloader.CvhDownloaderException;
import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.candidate.DownloadFormat;
import com.orinuno.cvh.downloader.ffmpeg.FfmpegRemuxer;
import com.orinuno.cvh.downloader.fs.LocalFsDestination;
import com.orinuno.cvh.downloader.hls.HlsMasterPlaylistResolver;
import com.orinuno.cvh.downloader.hls.HlsMediaPlaylist;
import com.orinuno.cvh.downloader.segment.ParallelSegmentDownloader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * HLS pipeline:
 *
 * <ol>
 *   <li>Fetch the master/media manifest (system {@link HttpClient}).
 *   <li>Resolve to a media playlist using {@link HlsMasterPlaylistResolver}.
 *   <li>Download every segment in parallel via {@link ParallelSegmentDownloader}.
 *   <li>Remux with {@link FfmpegRemuxer} concat-demuxer → MP4 stream copy.
 *   <li>Cleanup segment dir + commit final file.
 * </ol>
 */
@Slf4j
public final class HlsStrategy implements DownloadStrategy {

    private final WebClient webClient;
    private final CvhDownloaderConfig config;
    private final LocalFsDestination fs;
    private final FfmpegRemuxer ffmpeg;
    private final HttpClient javaHttpClient;

    public HlsStrategy(
            WebClient webClient,
            CvhDownloaderConfig config,
            LocalFsDestination fs,
            FfmpegRemuxer ffmpeg) {
        this.webClient = webClient;
        this.config = config;
        this.fs = fs;
        this.ffmpeg = ffmpeg;
        this.javaHttpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    @Override
    public boolean supports(DownloadCandidate candidate) {
        return candidate.format() == DownloadFormat.HLS;
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
                            "HLS candidate must use https:// scheme"));
        }
        Path segmentDir = targetMp4Path.resolveSibling(targetMp4Path.getFileName() + ".segments");
        return Mono.fromCallable(() -> resolvePlaylist(candidate.url(), request.referer()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        playlist -> {
                            if (playlist.isEmpty()) {
                                return Mono.error(
                                        new CvhDownloaderException(
                                                CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                                                "HLS media playlist had no segments"));
                            }
                            progress.totalSegments(playlist.size());
                            return Mono.fromCallable(() -> fs.ensureDir(segmentDir))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(
                                            dir ->
                                                    new ParallelSegmentDownloader(webClient, config)
                                                            .downloadAll(
                                                                    playlist.segmentUrls(),
                                                                    dir,
                                                                    request.referer(),
                                                                    progress))
                                    .flatMap(
                                            files ->
                                                    Mono.fromCallable(
                                                                    () ->
                                                                            remux(
                                                                                    segmentDir,
                                                                                    files,
                                                                                    targetMp4Path))
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic()));
                        })
                .doFinally(signal -> cleanupSegmentDir(segmentDir))
                .onErrorMap(
                        ex ->
                                ex instanceof CvhDownloaderException
                                        ? ex
                                        : new CvhDownloaderException(
                                                CvhDownloaderErrorCodes.CVH_DL_NETWORK,
                                                "HLS pipeline failed: " + ex.getMessage(),
                                                ex));
    }

    private HlsMediaPlaylist resolvePlaylist(String manifestUrl, String referer)
            throws IOException {
        byte[] manifest = fetchManifest(manifestUrl, referer);
        HlsMasterPlaylistResolver resolver =
                new HlsMasterPlaylistResolver(
                        javaHttpClient, HlsMasterPlaylistResolver.ResolverConfig.defaults());
        return resolver.resolve(manifestUrl, manifest);
    }

    private byte[] fetchManifest(String url, String referer) throws IOException {
        try {
            HttpResponse<byte[]> response =
                    javaHttpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofSeconds(15))
                                    .header("Referer", referer)
                                    .header("User-Agent", config.userAgent())
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HLS manifest fetch returned HTTP " + response.statusCode());
            }
            return response.body() != null ? response.body() : new byte[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching HLS manifest", e);
        }
    }

    private Path remux(Path segmentDir, List<Path> segments, Path targetMp4Path) throws Exception {
        FfmpegRemuxer.RemuxOptions options =
                new FfmpegRemuxer.RemuxOptions(
                        config.ffmpegBinary(), config.ffmpegTimeoutSeconds());
        return ffmpeg.remuxConcatDemuxer(segmentDir, segments, targetMp4Path, options);
    }

    private void cleanupSegmentDir(Path segmentDir) {
        try {
            if (Files.exists(segmentDir)) {
                try (var stream = Files.walk(segmentDir)) {
                    stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                            .forEach(fs::deleteIfExists);
                }
            }
        } catch (IOException ignored) {
            log.warn("Failed to cleanup segment dir {}", segmentDir);
        }
    }
}
