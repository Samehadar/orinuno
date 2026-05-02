package com.orinuno.model.dto;

/**
 * Minimal subset of Kodik /list result fields exposed via Orinuno's proxy. parser-kodik uses this
 * to decide what to enqueue (gap-fill, ongoing). Avoids forwarding the entire Kodik payload
 * (including obfuscated kodik link, internal flags, etc.) and keeps the contract stable across
 * Kodik schema drift.
 */
public record KodikListItemView(
        String id,
        String type,
        String title,
        String titleOrig,
        String otherTitle,
        Integer year,
        String kinopoiskId,
        String imdbId,
        String shikimoriId,
        String posterUrl,
        Integer lastSeason,
        Integer lastEpisode,
        Integer episodesCount,
        String animeStatus,
        String dramaStatus,
        String allStatus,
        String quality,
        String updatedAt) {}
