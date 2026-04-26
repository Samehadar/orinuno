package com.orinuno.service.requestlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.model.dto.ParseRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestHashServiceTest {

    private final RequestHashService service = new RequestHashService();

    @Test
    @DisplayName("identical payloads produce identical hash")
    void identicalPayloadsProduceIdenticalHash() {
        ParseRequestDto a =
                ParseRequestDto.builder()
                        .title("Naruto")
                        .kinopoiskId("12345")
                        .decodeLinks(true)
                        .build();
        ParseRequestDto b =
                ParseRequestDto.builder()
                        .title("Naruto")
                        .kinopoiskId("12345")
                        .decodeLinks(true)
                        .build();

        RequestHashService.Result ra = service.compute(a);
        RequestHashService.Result rb = service.compute(b);

        assertThat(ra.hash()).isEqualTo(rb.hash());
        assertThat(ra.canonicalJson()).isEqualTo(rb.canonicalJson());
    }

    @Test
    @DisplayName("title is trimmed and lowercased")
    void titleNormalization() {
        ParseRequestDto raw =
                ParseRequestDto.builder().title("  NaRuTo  ").decodeLinks(false).build();
        ParseRequestDto canonical =
                ParseRequestDto.builder().title("naruto").decodeLinks(false).build();

        assertThat(service.compute(raw).hash()).isEqualTo(service.compute(canonical).hash());
    }

    @Test
    @DisplayName("different decodeLinks produce different hash")
    void decodeLinksAffectsHash() {
        ParseRequestDto withDecode =
                ParseRequestDto.builder().kinopoiskId("12345").decodeLinks(true).build();
        ParseRequestDto withoutDecode =
                ParseRequestDto.builder().kinopoiskId("12345").decodeLinks(false).build();

        assertThat(service.compute(withDecode).hash())
                .isNotEqualTo(service.compute(withoutDecode).hash());
    }

    @Test
    @DisplayName("different kinopoiskIds produce different hash")
    void differentIdsProduceDifferentHash() {
        ParseRequestDto a = ParseRequestDto.builder().kinopoiskId("11111").build();
        ParseRequestDto b = ParseRequestDto.builder().kinopoiskId("22222").build();

        assertThat(service.compute(a).hash()).isNotEqualTo(service.compute(b).hash());
    }

    @Test
    @DisplayName("blank ids are treated as null")
    void blankIdsTreatedAsNull() {
        ParseRequestDto withBlank =
                ParseRequestDto.builder()
                        .title("naruto")
                        .kinopoiskId("   ")
                        .imdbId("")
                        .decodeLinks(true)
                        .build();
        ParseRequestDto withNull =
                ParseRequestDto.builder().title("naruto").decodeLinks(true).build();

        assertThat(service.compute(withBlank).hash()).isEqualTo(service.compute(withNull).hash());
    }

    @Test
    @DisplayName("hash is deterministic across invocations")
    void hashIsDeterministic() {
        ParseRequestDto req =
                ParseRequestDto.builder()
                        .title("attack on titan")
                        .kinopoiskId("777777")
                        .decodeLinks(true)
                        .build();

        String h1 = service.compute(req).hash();
        String h2 = service.compute(req).hash();
        String h3 = service.compute(req).hash();

        assertThat(h1).isEqualTo(h2).isEqualTo(h3);
        assertThat(h1).hasSize(64);
    }

    @Test
    @DisplayName("canonical JSON does not include null fields")
    void canonicalJsonOmitsNulls() {
        ParseRequestDto req = ParseRequestDto.builder().title("naruto").decodeLinks(true).build();

        String json = service.compute(req).canonicalJson();

        assertThat(json).doesNotContain("kinopoiskId");
        assertThat(json).doesNotContain("imdbId");
        assertThat(json).doesNotContain("shikimoriId");
        assertThat(json).contains("\"title\":\"naruto\"");
    }
}
