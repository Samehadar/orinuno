package com.orinuno.service.dumps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.client.dto.KodikSearchResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikDumpStreamingReaderTest {

    @Test
    @DisplayName("readArray streams every well-formed element + reports malformed entry as skipped")
    void readArrayStreamsEveryElement() throws IOException {
        try (InputStream stream = openFixture("films-fixture.json")) {
            List<KodikSearchResponse.Result> seen = new ArrayList<>();
            KodikDumpStreamingReader.StreamingResult result =
                    KodikDumpStreamingReader.readArray(stream, seen::add);

            assertThat(result.processed()).isEqualTo(4);
            assertThat(result.skipped()).isZero();
            assertThat(result.total()).isEqualTo(4);
            assertThat(seen)
                    .extracting(KodikSearchResponse.Result::getId)
                    .containsExactly("movie-117218", "movie-99", null, "serial-42");
            assertThat(seen.get(3).getSeasons()).hasSize(1);
            assertThat(seen.get(3).getSeasons().get("1").getEpisodes()).hasSize(2);
        }
    }

    @Test
    @DisplayName("readArray rejects payload that is not a JSON array at the root")
    void readArrayRejectsNonArrayRoot() {
        InputStream notAnArray =
                new ByteArrayInputStream("{\"not\":\"an array\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> KodikDumpStreamingReader.readArray(notAnArray, r -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Expected JSON array");
    }

    @Test
    @DisplayName("readArray counts a consumer-thrown RuntimeException as skipped, keeps streaming")
    void readArrayKeepsStreamingWhenConsumerThrows() throws IOException {
        try (InputStream stream = openFixture("films-fixture.json")) {
            List<String> seen = new ArrayList<>();
            KodikDumpStreamingReader.StreamingResult result =
                    KodikDumpStreamingReader.readArray(
                            stream,
                            r -> {
                                if ("movie-99".equals(r.getId())) {
                                    throw new RuntimeException("boom on movie-99");
                                }
                                seen.add(r.getId());
                            });

            assertThat(seen).contains("movie-117218", "serial-42");
            assertThat(result.processed()).isEqualTo(3);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("readArray handles an empty array gracefully")
    void readArrayEmpty() throws IOException {
        try (InputStream stream = new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8))) {
            KodikDumpStreamingReader.StreamingResult result =
                    KodikDumpStreamingReader.readArray(stream, r -> {});
            assertThat(result.processed()).isZero();
            assertThat(result.skipped()).isZero();
        }
    }

    private static InputStream openFixture(String name) {
        InputStream stream = KodikDumpStreamingReaderTest.class.getResourceAsStream(name);
        if (stream == null) {
            throw new IllegalStateException("Test fixture not found on classpath: " + name);
        }
        return stream;
    }
}
