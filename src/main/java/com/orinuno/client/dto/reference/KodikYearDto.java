package com.orinuno.client.dto.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Item from {@code POST /years}. The Kodik years endpoint exposes a {@code year} field (not {@code
 * title}) together with the usage counter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KodikYearDto(Integer year, Integer count) {}
