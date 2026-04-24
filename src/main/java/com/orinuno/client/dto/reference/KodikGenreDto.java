package com.orinuno.client.dto.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Item from {@code POST /genres}. Intentionally without {@code id} — the Kodik genre endpoint only
 * returns {@code title} and usage counter (see {@code BACKLOG.md} §4).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikGenreDto(String title, Integer count) {}
