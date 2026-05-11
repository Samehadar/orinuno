package com.orinuno.cvh.downloader;

import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.candidate.DownloadFormat;
import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of a successful {@link CvhDownloader#download} call.
 *
 * @param filepath final MP4 location on disk (already moved out of any temp directory)
 * @param bytesWritten file size on disk after completion
 * @param formatUsed which candidate format actually produced the file
 * @param durationMs wall-clock time spent in the pipeline
 * @param attemptedCandidates ordered list of candidates the downloader tried (last one is the
 *     winning candidate; everything before it failed and triggered a fallback)
 */
public record CvhDownloadResult(
        Path filepath,
        long bytesWritten,
        DownloadFormat formatUsed,
        long durationMs,
        List<DownloadCandidate> attemptedCandidates) {

    public CvhDownloadResult {
        attemptedCandidates =
                attemptedCandidates == null ? List.of() : List.copyOf(attemptedCandidates);
    }
}
