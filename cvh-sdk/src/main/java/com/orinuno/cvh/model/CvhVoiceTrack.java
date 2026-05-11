package com.orinuno.cvh.model;

import jakarta.annotation.Nullable;

/**
 * One voice-track entry returned by the CVH title endpoint.
 *
 * <p>{@code vkId} is the numeric ID consumed by the second plapi hop ({@code /sv/video/{vkId}}). It
 * is the same value as {@code unitedVideoId} in the sources response. It is NOT the public {@code
 * oid_id} format used by vkvideo.ru.
 */
public record CvhVoiceTrack(
        @Nullable String cvhId,
        @Nullable String vkId,
        @Nullable String voiceStudio,
        @Nullable String voiceType) {}
