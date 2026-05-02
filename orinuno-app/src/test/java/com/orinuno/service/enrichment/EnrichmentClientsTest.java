package com.orinuno.service.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikContentEnrichment;
import com.orinuno.service.discovery.shikimori.ShikimoriClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EnrichmentClientsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shikimoriToRowExtractsTitleAndScore() throws Exception {
        JsonNode node =
                objectMapper.readTree(
                        "{\"name\":\"Naruto\",\"russian\":\"Наруто\","
                                + "\"japanese\":\"NARUTO -ナルト-\",\"description\":\"plot\","
                                + "\"score\":\"7.91\"}");
        KodikContent content = new KodikContent();
        content.setId(1L);
        KodikContentEnrichment row = ShikimoriEnrichmentClient.toRow(content, 12345L, node);
        assertThat(row.getContentId()).isEqualTo(1L);
        assertThat(row.getSource()).isEqualTo("SHIKIMORI");
        assertThat(row.getExternalId()).isEqualTo("12345");
        assertThat(row.getTitleEnglish()).isEqualTo("Naruto");
        assertThat(row.getTitleNative()).isEqualTo("NARUTO -ナルト-");
        assertThat(row.getTitleRussian()).isEqualTo("Наруто");
        assertThat(row.getDescription()).isEqualTo("plot");
        assertThat(row.getScore().toPlainString()).isEqualTo("7.91");
        assertThat(row.getRawPayload()).contains("Naruto");
    }

    @Test
    void shikimoriClientSkipsBlankShikimoriId() {
        ShikimoriClient client = mock(ShikimoriClient.class);
        ShikimoriEnrichmentClient enricher = new ShikimoriEnrichmentClient(client);
        KodikContent content = new KodikContent();
        content.setShikimoriId("");
        StepVerifier.create(enricher.fetch(content)).verifyComplete();
    }

    @Test
    void shikimoriClientSkipsNonNumericShikimoriId() {
        ShikimoriClient client = mock(ShikimoriClient.class);
        ShikimoriEnrichmentClient enricher = new ShikimoriEnrichmentClient(client);
        KodikContent content = new KodikContent();
        content.setShikimoriId("z-not-a-number");
        StepVerifier.create(enricher.fetch(content)).verifyComplete();
    }

    @Test
    void shikimoriClientAbsorbsHttpErrors() {
        ShikimoriClient client = mock(ShikimoriClient.class);
        when(client.fetchAnime(1L)).thenReturn(Mono.error(new RuntimeException("404")));
        ShikimoriEnrichmentClient enricher = new ShikimoriEnrichmentClient(client);
        KodikContent content = new KodikContent();
        content.setId(1L);
        content.setShikimoriId("1");
        StepVerifier.create(enricher.fetch(content)).verifyComplete();
    }

    @Test
    void kinopoiskClientDerivesRowFromContentFields() {
        KinopoiskEnrichmentClient enricher = new KinopoiskEnrichmentClient();
        KodikContent content = new KodikContent();
        content.setId(7L);
        content.setKinopoiskId("kp-123");
        content.setTitle("Наруто");
        content.setTitleOrig("Naruto");
        content.setKinopoiskRating(7.5);
        StepVerifier.create(enricher.fetch(content))
                .assertNext(
                        row -> {
                            assertThat(row.getSource()).isEqualTo("KINOPOISK");
                            assertThat(row.getExternalId()).isEqualTo("kp-123");
                            assertThat(row.getTitleRussian()).isEqualTo("Наруто");
                            assertThat(row.getTitleEnglish()).isEqualTo("Naruto");
                            assertThat(row.getScore().doubleValue()).isEqualTo(7.5);
                        })
                .verifyComplete();
    }

    @Test
    void kinopoiskClientSkipsWithoutKinopoiskId() {
        KinopoiskEnrichmentClient enricher = new KinopoiskEnrichmentClient();
        KodikContent content = new KodikContent();
        StepVerifier.create(enricher.fetch(content)).verifyComplete();
    }

    @Test
    void malToRowParsesJikanEnvelope() throws Exception {
        JsonNode envelope =
                objectMapper.readTree(
                        "{\"data\":{\"mal_id\":20,\"title_japanese\":\"NARUTO\","
                                + "\"title_english\":\"Naruto\",\"synopsis\":\"...\","
                                + "\"score\":7.93}}");
        KodikContent content = new KodikContent();
        content.setId(11L);
        KodikContentEnrichment row = MalEnrichmentClient.toRow(content, envelope);
        assertThat(row.getSource()).isEqualTo("MAL");
        assertThat(row.getExternalId()).isEqualTo("20");
        assertThat(row.getTitleEnglish()).isEqualTo("Naruto");
        assertThat(row.getTitleNative()).isEqualTo("NARUTO");
        assertThat(row.getScore().doubleValue()).isEqualTo(7.93);
    }
}
