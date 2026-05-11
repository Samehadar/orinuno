package com.orinuno.cvh.downloader;

import jakarta.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe live progress for one {@link CvhDownloader#download} call. The downloader mutates the
 * counters as work proceeds; callers can poll fields from any thread for UI / metrics.
 */
public final class CvhDownloadProgress {

    public enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private final AtomicReference<String> currentCandidate = new AtomicReference<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicInteger totalSegments = new AtomicInteger();
    private final AtomicInteger downloadedSegments = new AtomicInteger();

    public Status status() {
        return status.get();
    }

    public void status(Status s) {
        this.status.set(s);
    }

    @Nullable
    public String currentCandidate() {
        return currentCandidate.get();
    }

    public void currentCandidate(@Nullable String c) {
        this.currentCandidate.set(c);
    }

    public long totalBytes() {
        return totalBytes.get();
    }

    public void totalBytes(long bytes) {
        this.totalBytes.set(bytes);
    }

    public long downloadedBytes() {
        return downloadedBytes.get();
    }

    public void addDownloadedBytes(long delta) {
        downloadedBytes.addAndGet(delta);
    }

    public int totalSegments() {
        return totalSegments.get();
    }

    public void totalSegments(int n) {
        this.totalSegments.set(n);
    }

    public int downloadedSegments() {
        return downloadedSegments.get();
    }

    public void incrementDownloadedSegments() {
        downloadedSegments.incrementAndGet();
    }

    public void resetForNewCandidate(@Nullable String candidate) {
        currentCandidate.set(candidate);
        totalBytes.set(0);
        downloadedBytes.set(0);
        totalSegments.set(0);
        downloadedSegments.set(0);
    }
}
