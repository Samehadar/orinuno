package com.orinuno.cvh.model;

/** One track plus its resolved CDN URLs. */
public record TrackWithSources(CvhVoiceTrack track, CvhVideoSources sources) {}
