/*
 * DownloadProgress — ADR 0021 §D1b-3.
 *
 * Shared mutable progress tracker updated by PlaywrightVideoFetcher
 * (HLS segments) or the WebClient direct-mp4 fallback. Promoted to a
 * top-level class in source-kodik (lifted from the nested
 * VideoDownloadService.DownloadProgress in orinuno-app) so the Playwright
 * port can reference it without pulling the still-orinuno-app-resident
 * VideoDownloadService (Block C3 scope). When VideoDownloadService
 * follows over in C3, it will import this type instead of re-introducing
 * a nested copy.
 */
package com.orinuno.source.kodik.service.download;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DownloadProgress {
    private final AtomicInteger totalSegments = new AtomicInteger(0);
    private final AtomicInteger downloadedSegments = new AtomicInteger(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong expectedTotalBytes = new AtomicLong(0);

    public void setTotalSegments(int total) {
        totalSegments.set(total);
    }

    public void incrementDownloaded() {
        downloadedSegments.incrementAndGet();
    }

    public void addBytes(long bytes) {
        totalBytes.addAndGet(bytes);
    }

    public void setExpectedTotalBytes(long bytes) {
        if (bytes > 0) expectedTotalBytes.set(bytes);
    }

    public int getTotalSegments() {
        return totalSegments.get();
    }

    public int getDownloadedSegments() {
        return downloadedSegments.get();
    }

    public long getTotalBytes() {
        return totalBytes.get();
    }

    public long getExpectedTotalBytes() {
        return expectedTotalBytes.get();
    }
}
