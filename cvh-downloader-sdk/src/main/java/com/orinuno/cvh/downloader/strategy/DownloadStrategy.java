package com.orinuno.cvh.downloader.strategy;

import com.orinuno.cvh.downloader.CvhDownloadProgress;
import com.orinuno.cvh.downloader.CvhDownloadRequest;
import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

/**
 * One download path (MP4 direct / HLS / DASH). The orchestrator iterates over impls until one wins.
 */
public interface DownloadStrategy {

    boolean supports(DownloadCandidate candidate);

    /**
     * Downloads {@code candidate}, writing the final MP4 to a path inside {@code targetMp4Path}'s
     * parent directory. Returns the path actually written on success. Failures must propagate as
     * either {@link com.orinuno.cvh.downloader.CvhDownloaderException} or any other {@link
     * Throwable} which the orchestrator interprets as a "next candidate, please" signal.
     */
    Mono<Path> download(
            DownloadCandidate candidate,
            CvhDownloadRequest request,
            Path targetMp4Path,
            CvhDownloadProgress progress);
}
