package com.orinuno.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kodik.sdk.drift.DtoFieldExtractor;
import com.orinuno.client.dto.reference.KodikCountryDto;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikQualityDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DtoFieldExtractorTest {

    @Test
    @DisplayName("uses @JsonProperty value when present")
    void usesJsonPropertyValue() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(WithJsonProperty.class);
        assertThat(fields).containsExactlyInAnyOrder("custom_name", "also_snake");
    }

    @Test
    @DisplayName("falls back to snake_case when @JsonProperty is missing")
    void fallsBackToSnakeCase() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(PlainCamelCase.class);
        assertThat(fields)
                .containsExactlyInAnyOrder("first_name", "last_name", "http_response_code");
    }

    @Test
    @DisplayName("includes @JsonAlias alternates alongside primary name")
    void includesAliases() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(WithAliases.class);
        assertThat(fields).contains("mdl_id", "mydramalist_id", "drama_id");
    }

    @Test
    @DisplayName("skips @JsonIgnore fields")
    void skipsIgnored() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(WithIgnored.class);
        assertThat(fields).containsExactlyInAnyOrder("visible_field");
    }

    @Test
    @DisplayName("skips static fields")
    void skipsStatic() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(WithStatic.class);
        assertThat(fields).containsExactlyInAnyOrder("real_field");
    }

    @Test
    @DisplayName("returns cached instance on subsequent calls")
    void caches() {
        Set<String> first = DtoFieldExtractor.knownJsonFields(PlainCamelCase.class);
        Set<String> second = DtoFieldExtractor.knownJsonFields(PlainCamelCase.class);
        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("KodikSearchResponse.Result exposes all expected external ID fields")
    void kodikResultContainsExternalIds() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikSearchResponse.Result.class);
        assertThat(fields)
                .contains(
                        "kinopoisk_id",
                        "imdb_id",
                        "shikimori_id",
                        "mdl_id",
                        "worldart_link",
                        "worldart_animation_id",
                        "worldart_cinema_id");
    }

    @Test
    @DisplayName("KodikReferenceResponse envelope exposes time/total/results")
    void kodikReferenceEnvelopeFields() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikReferenceResponse.class);
        assertThat(fields).containsExactlyInAnyOrder("time", "total", "results");
    }

    @Test
    @DisplayName("KodikTranslationDto record: id/title/count")
    void translationRecordFields() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikTranslationDto.class);
        assertThat(fields).containsExactlyInAnyOrder("id", "title", "count");
    }

    @Test
    @DisplayName("KodikGenreDto record: title/count, intentionally no id")
    void genreRecordFields() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikGenreDto.class);
        assertThat(fields).containsExactlyInAnyOrder("title", "count");
    }

    @Test
    @DisplayName("KodikCountryDto record: title/count, intentionally no id")
    void countryRecordFields() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikCountryDto.class);
        assertThat(fields).containsExactlyInAnyOrder("title", "count");
    }

    @Test
    @DisplayName("KodikYearDto record: year/count (not title)")
    void yearRecordFields() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikYearDto.class);
        assertThat(fields).containsExactlyInAnyOrder("year", "count");
    }

    @Test
    @DisplayName("KodikQualityDto record: title/count (v2 variant)")
    void qualityRecordFields() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikQualityDto.class);
        assertThat(fields).containsExactlyInAnyOrder("title", "count");
    }

    @SuppressWarnings("unused")
    static class WithJsonProperty {
        @JsonProperty("custom_name")
        private String ignoredName;

        @JsonProperty("also_snake")
        private String another;
    }

    @SuppressWarnings("unused")
    static class PlainCamelCase {
        private String firstName;
        private String lastName;
        private String httpResponseCode;
    }

    @SuppressWarnings("unused")
    static class WithAliases {
        @JsonProperty("mdl_id")
        @JsonAlias({"mydramalist_id", "drama_id"})
        private String id;
    }

    @SuppressWarnings("unused")
    static class WithIgnored {
        @JsonIgnore private String hiddenField;

        private String visibleField;
    }

    @SuppressWarnings("unused")
    static class WithStatic {
        public static final String CONSTANT = "x";
        private String realField;
    }
}
