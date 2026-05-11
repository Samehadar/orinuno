package com.orinuno.cvh.downloader;

import com.orinuno.cvh.downloader.candidate.CandidateChain;
import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.ffmpeg.FfmpegRemuxer;
import com.orinuno.cvh.downloader.fs.FilenameSanitizer;
import com.orinuno.cvh.downloader.fs.LocalFsDestination;
import com.orinuno.cvh.downloader.strategy.DashStrategy;
import com.orinuno.cvh.downloader.strategy.DownloadStrategy;
import com.orinuno.cvh.downloader.strategy.HlsStrategy;
import com.orinuno.cvh.downloader.strategy.Mp4DirectStrategy;
import com.orinuno.cvh.model.CvhVideoSources;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public facade. Builder constructs the strategy stack once and reuses it across calls. Each {@link
 * #download} call returns a fresh {@link CvhDownloadProgress} so callers can poll progress
 * concurrently for multiple downloads.
 *
 * <pre>{@code
 * CvhDownloader downloader = CvhDownloader.builder().build();
 * CvhDownloadResult r = downloader.download(
 *         sources,
 *         CvhDownloadRequest.builder()
 *                 .filenameHint("all-you-need-is-kill")
 *                 .referer("https://jut-su.works/")
 *                 .build())
 *     .block();
 * }</pre>
 */
@Slf4j
public final class CvhDownloader {

    private final CvhDownloaderConfig config;
    private final LocalFsDestination fs;
    private final List<DownloadStrategy> strategies;

    private CvhDownloader(
            CvhDownloaderConfig config, LocalFsDestination fs, List<DownloadStrategy> strategies) {
        this.config = config;
        this.fs = fs;
        this.strategies = List.copyOf(strategies);
    }

    public static Builder builder() {
        return new Builder();
    }

    public CvhDownloaderConfig config() {
        return config;
    }

    public LocalFsDestination fs() {
        return fs;
    }

    public List<DownloadStrategy> strategies() {
        return strategies;
    }

    /**
     * Download {@code sources} into the configured base directory. Iterates {@link
     * CandidateChain#from(CvhVideoSources, QualityPreference)} (or {@link
     * CvhDownloadRequest#customChain()} if non-null), trying each candidate in turn. Returns the
     * first successful {@link CvhDownloadResult}; if every candidate fails, fails the {@link Mono}
     * with {@link CvhDownloaderErrorCodes#CVH_DL_ALL_FAILED}.
     */
    public Mono<CvhDownloadResult> download(CvhVideoSources sources, CvhDownloadRequest request) {
        return downloadWithProgress(sources, request, new CvhDownloadProgress());
    }

    public Mono<CvhDownloadResult> downloadWithProgress(
            CvhVideoSources sources, CvhDownloadRequest request, CvhDownloadProgress progress) {
        if (request == null) {
            return Mono.error(
                    new CvhDownloaderException(
                            CvhDownloaderErrorCodes.CVH_DL_INVALID_REQUEST,
                            "request must not be null"));
        }
        List<DownloadCandidate> chain =
                request.customChain() != null && !request.customChain().isEmpty()
                        ? request.customChain()
                        : CandidateChain.from(sources, request.preference());
        if (chain.isEmpty()) {
            return Mono.error(
                    new CvhDownloaderException(
                            CvhDownloaderErrorCodes.CVH_DL_NO_CANDIDATES,
                            "No playable URLs in CvhVideoSources"));
        }
        String safeName = FilenameSanitizer.sanitize(request.filenameHint());
        Path targetMp4Path = fs.resolveSafe(safeName + ".mp4");
        progress.status(CvhDownloadProgress.Status.IN_PROGRESS);
        long start = System.currentTimeMillis();
        List<DownloadCandidate> attempted = new ArrayList<>();
        return tryNext(chain.iterator(), request, targetMp4Path, progress, attempted)
                .map(
                        path ->
                                new CvhDownloadResult(
                                        path,
                                        sizeOrZero(path),
                                        attempted.get(attempted.size() - 1).format(),
                                        System.currentTimeMillis() - start,
                                        attempted))
                .doOnNext(r -> progress.status(CvhDownloadProgress.Status.COMPLETED))
                .doOnError(ex -> progress.status(CvhDownloadProgress.Status.FAILED));
    }

    private Mono<Path> tryNext(
            Iterator<DownloadCandidate> it,
            CvhDownloadRequest request,
            Path targetMp4Path,
            CvhDownloadProgress progress,
            List<DownloadCandidate> attempted) {
        if (!it.hasNext()) {
            return Mono.error(
                    new CvhDownloaderException(
                            CvhDownloaderErrorCodes.CVH_DL_ALL_FAILED,
                            "Every candidate in the fallback chain failed"));
        }
        DownloadCandidate candidate = it.next();
        DownloadStrategy strategy =
                strategies.stream().filter(s -> s.supports(candidate)).findFirst().orElse(null);
        if (strategy == null) {
            log.warn("No strategy for candidate format={}, skipping", candidate.format());
            return tryNext(it, request, targetMp4Path, progress, attempted);
        }
        attempted.add(candidate);
        progress.resetForNewCandidate(candidate.url());
        log.info(
                "Attempting candidate format={} url-host={}",
                candidate.format(),
                hostOf(candidate.url()));
        return strategy.download(candidate, request, targetMp4Path, progress)
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "Candidate failed ({}): {} — trying next",
                                    candidate.format(),
                                    ex.toString());
                            return tryNext(it, request, targetMp4Path, progress, attempted);
                        });
    }

    private static long sizeOrZero(Path path) {
        try {
            return java.nio.file.Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    public static final class Builder {
        private CvhDownloaderConfig config;
        private WebClient.Builder webClientBuilder;
        private FfmpegRemuxer ffmpeg;

        private Builder() {}

        public Builder config(CvhDownloaderConfig config) {
            this.config = config;
            return this;
        }

        public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        public Builder ffmpeg(FfmpegRemuxer ffmpeg) {
            this.ffmpeg = ffmpeg;
            return this;
        }

        public CvhDownloader build() {
            CvhDownloaderConfig effectiveConfig =
                    config != null ? config : CvhDownloaderConfig.builder().build();
            WebClient.Builder builder =
                    webClientBuilder != null ? webClientBuilder : WebClient.builder();
            WebClient httpClient =
                    builder.defaultHeader("User-Agent", effectiveConfig.userAgent()).build();
            LocalFsDestination fs = new LocalFsDestination(effectiveConfig.outputBaseDir());
            FfmpegRemuxer effectiveFfmpeg =
                    ffmpeg != null
                            ? ffmpeg
                            : new FfmpegRemuxer(
                                    new FfmpegRemuxer.RemuxOptions(
                                            effectiveConfig.ffmpegBinary(),
                                            effectiveConfig.ffmpegTimeoutSeconds()),
                                    com.orinuno.cvh.downloader.ffmpeg.ProcessExecutor.system());
            List<DownloadStrategy> strategies =
                    List.of(
                            new Mp4DirectStrategy(effectiveConfig, fs),
                            new HlsStrategy(httpClient, effectiveConfig, fs, effectiveFfmpeg),
                            new DashStrategy(effectiveConfig, effectiveFfmpeg));
            return new CvhDownloader(effectiveConfig, fs, strategies);
        }
    }
}
