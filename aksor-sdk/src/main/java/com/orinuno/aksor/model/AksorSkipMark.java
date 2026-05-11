package com.orinuno.aksor.model;

import jakarta.annotation.Nullable;

/**
 * Optional opening / ending marker reported by the host's videos API.
 *
 * @param timeSec start offset from the beginning of the episode, in seconds
 * @param lengthSec duration of the segment, in seconds
 */
public record AksorSkipMark(@Nullable Integer timeSec, @Nullable Integer lengthSec) {}
