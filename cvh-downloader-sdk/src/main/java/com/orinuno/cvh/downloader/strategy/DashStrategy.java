package com.orinuno.cvh.downloader.strategy;

import com.orinuno.cvh.downloader.CvhDownloadProgress;
import com.orinuno.cvh.downloader.CvhDownloadRequest;
import com.orinuno.cvh.downloader.CvhDownloaderConfig;
import com.orinuno.cvh.downloader.CvhDownloaderErrorCodes;
import com.orinuno.cvh.downloader.CvhDownloaderException;
import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.candidate.DownloadFormat;
import com.orinuno.cvh.downloader.ffmpeg.FfmpegRemuxer;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Delegates DASH manifest handling to ffmpeg via {@code -i manifest.mpd -c copy out.mp4}. The SDK
 * does not parse MPDs natively — ffmpeg supports DASH adaptive bitrate selection out of the box and
 * respects the {@code Referer} header which CVH's signed CDN requires.
 *
 * <p>Progress granularity is coarse (only the process exit code is observed). Callers needing
 * per-segment metrics should prefer the {@link HlsStrategy} for the same title.
 */
@Slf4j
public final class DashStrategy implements DownloadStrategy {

    private final CvhDownloaderConfig config;
    private final FfmpegRemuxer ffmpeg;

    public DashStrategy(CvhDownloaderConfig config, FfmpegRemuxer ffmpeg) {
        this.config = config;
        this.ffmpeg = ffmpeg;
    }

    @Override
    public boolean supports(DownloadCandidate candidate) {
        return candidate.format() == DownloadFormat.DASH;
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
                            "DASH candidate must use https:// scheme"));
        }
        FfmpegRemuxer.RemuxOptions options =
                new FfmpegRemuxer.RemuxOptions(
                        config.ffmpegBinary(), config.ffmpegTimeoutSeconds());
        return Mono.fromCallable(
                        () -> {
                            Files.createDirectories(targetMp4Path.getParent());
                            Path result =
                                    ffmpeg.downloadDirect(
                                            candidate.url(),
                                            targetMp4Path,
                                            request.referer(),
                                            config.userAgent(),
                                            options);
                            progress.addDownloadedBytes(Files.size(result));
                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        ex ->
                                ex instanceof CvhDownloaderException
                                        ? ex
                                        : new CvhDownloaderException(
                                                CvhDownloaderErrorCodes.CVH_DL_FFMPEG_FAILED,
                                                "DASH ffmpeg-direct failed: " + ex.getMessage(),
                                                ex));
    }
}
