package com.orinuno.cvh.downloader.candidate;

import com.orinuno.cvh.model.CvhVideoSources;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds an ordered list of {@link DownloadCandidate}s from a {@link CvhVideoSources} according to
 * a {@link QualityPreference}. Null / blank URLs in the source bundle are skipped silently.
 */
public final class CandidateChain {

    private CandidateChain() {}

    public static List<DownloadCandidate> from(
            CvhVideoSources sources, QualityPreference preference) {
        if (sources == null) {
            return List.of();
        }
        if (preference == null) {
            preference = QualityPreference.BEST_FIRST;
        }
        List<DownloadCandidate> mp4 = mp4Ladder(sources);
        DownloadCandidate hls = candidate(sources.hlsUrl(), DownloadFormat.HLS);
        DownloadCandidate dash = candidate(sources.dashUrl(), DownloadFormat.DASH);

        List<DownloadCandidate> chain = new ArrayList<>();
        switch (preference) {
            case BEST_FIRST -> {
                chain.addAll(mp4);
                if (hls != null) chain.add(hls);
                if (dash != null) chain.add(dash);
            }
            case SMALLEST_FIRST -> {
                List<DownloadCandidate> reversed = new ArrayList<>(mp4);
                Collections.reverse(reversed);
                chain.addAll(reversed);
                if (hls != null) chain.add(hls);
                if (dash != null) chain.add(dash);
            }
            case HLS_FIRST -> {
                if (hls != null) chain.add(hls);
                if (dash != null) chain.add(dash);
                chain.addAll(mp4);
            }
        }
        return List.copyOf(chain);
    }

    private static List<DownloadCandidate> mp4Ladder(CvhVideoSources sources) {
        List<DownloadCandidate> out = new ArrayList<>(6);
        addIfPresent(out, sources.mp4_1080p(), Mp4Quality.P1080);
        addIfPresent(out, sources.mp4_720p(), Mp4Quality.P720);
        addIfPresent(out, sources.mp4_480p(), Mp4Quality.P480);
        addIfPresent(out, sources.mp4_360p(), Mp4Quality.P360);
        addIfPresent(out, sources.mp4_240p(), Mp4Quality.P240);
        addIfPresent(out, sources.mp4_144p(), Mp4Quality.P144);
        return out;
    }

    private static void addIfPresent(List<DownloadCandidate> out, String url, Mp4Quality quality) {
        if (url != null && !url.isBlank()) {
            out.add(DownloadCandidate.mp4(url, quality));
        }
    }

    private static DownloadCandidate candidate(String url, DownloadFormat format) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return new DownloadCandidate(url, format, null);
    }
}
