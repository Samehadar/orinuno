package com.orinuno.client.dto.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Item from {@code POST /qualities/v2}. The v2 endpoint returns a usage counter alongside {@code
 * title}; the legacy {@code /qualities} (v1) returns a flat list with {@code title} only and is not
 * used by orinuno.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikQualityDto(String title, Integer count) {}
