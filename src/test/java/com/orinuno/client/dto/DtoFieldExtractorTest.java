package com.orinuno.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
