package com.orinuno.client.dto.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Item from {@code POST /translations/v2}. Translations are identified by a numeric id and include
 * a usage counter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikTranslationDto(Integer id, String title, Integer count) {}
