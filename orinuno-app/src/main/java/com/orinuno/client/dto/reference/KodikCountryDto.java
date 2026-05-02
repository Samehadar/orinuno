package com.orinuno.client.dto.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Item from {@code POST /countries}. Like {@link KodikGenreDto}, has no {@code id} — only {@code
 * title} and usage counter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikCountryDto(String title, Integer count) {}
