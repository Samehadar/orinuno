package com.orinuno.cvh.downloader.candidate;

/**
 * Built-in candidate ordering policies. Used by {@link CandidateChain#from} to turn a {@link
 * com.orinuno.cvh.model.CvhVideoSources} into an ordered fallback list.
 */
public enum QualityPreference {
    /** 1080p MP4 first → smaller MP4s → HLS → DASH. Default for desktop / wired clients. */
    BEST_FIRST,
    /** 144p MP4 first → growing MP4s → HLS → DASH. Default for bandwidth-constrained clients. */
    SMALLEST_FIRST,
    /** HLS first (adaptive bitrate), then DASH, then MP4 ladder. */
    HLS_FIRST
}
