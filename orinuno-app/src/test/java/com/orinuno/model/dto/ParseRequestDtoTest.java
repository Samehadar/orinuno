package com.orinuno.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParseRequestDtoTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void start() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    @Test
    @DisplayName("empty payload is rejected with the @AssertTrue message")
    void emptyPayloadFails() {
        ParseRequestDto dto = ParseRequestDto.builder().build();

        Set<ConstraintViolation<ParseRequestDto>> violations = validator.validate(dto);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("requires at least one of: title, kinopoiskId, imdbId, shikimoriId");
    }

    @Test
    @DisplayName("blank-only payload is rejected as well")
    void blankPayloadFails() {
        ParseRequestDto dto =
                ParseRequestDto.builder().title("   ").kinopoiskId("  ").imdbId("").build();

        Set<ConstraintViolation<ParseRequestDto>> violations = validator.validate(dto);

        assertThat(violations).hasSize(1);
    }

    @Test
    @DisplayName("title alone passes")
    void titleAlonePasses() {
        ParseRequestDto dto = ParseRequestDto.builder().title("Chainsaw Man").build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("kinopoisk id alone passes")
    void kinopoiskIdAlonePasses() {
        ParseRequestDto dto = ParseRequestDto.builder().kinopoiskId("326").build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("shikimori id alone passes")
    void shikimoriIdAlonePasses() {
        ParseRequestDto dto = ParseRequestDto.builder().shikimoriId("20").build();

        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    @DisplayName("decodeLinks alone (no identifiers) is rejected")
    void decodeLinksWithoutIdsFails() {
        ParseRequestDto dto = ParseRequestDto.builder().decodeLinks(true).build();

        assertThat(validator.validate(dto)).hasSize(1);
    }
}
