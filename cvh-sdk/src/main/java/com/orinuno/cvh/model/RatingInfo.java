package com.orinuno.cvh.model;

import jakarta.annotation.Nullable;

/**
 * Aggregated rating block lifted from the host page's JSON-LD {@code aggregateRating} node.
 *
 * <p>All fields are nullable — different hosts populate different subsets.
 */
public record RatingInfo(
        @Nullable String value,
        @Nullable String count,
        @Nullable String contentType,
        @Nullable String datePublished) {}
