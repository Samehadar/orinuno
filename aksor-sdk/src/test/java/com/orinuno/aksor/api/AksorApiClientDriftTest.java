package com.orinuno.aksor.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.aksor.drift.AksorDriftDetector;
import com.orinuno.aksor.drift.AksorDriftSignal;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AksorApiClient#mapQualities} emits the right drift signals. Lives next to {@link
 * AksorApiClient} so it can reach the package-private helper.
 */
class AksorApiClientDriftTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void missingQualitiesNodeEmitsSignal() throws Exception {
        AksorDriftDetector d = new AksorDriftDetector();
        try {
            AksorApiClient.mapQualities(M.readTree("{\"foo\":1}"), "abc", d);
        } catch (Exception ignored) {
            // expected: throws AKSOR_NO_QUALITIES
        }
        assertThat(d.snapshot().count(AksorDriftSignal.AKSOR_QUALITIES_MISSING)).isEqualTo(1);
    }

    @Test
    void allBlankQualitiesEmitsSignal() throws Exception {
        AksorDriftDetector d = new AksorDriftDetector();
        String body =
                "{\"qualities\":{\"q1080\":null,\"q720\":null,\"q480\":null,"
                        + "\"q360\":null,\"q2k\":null,\"q4k\":null}}";
        try {
            AksorApiClient.mapQualities(M.readTree(body), "abc", d);
        } catch (Exception ignored) {
            // expected
        }
        assertThat(d.snapshot().count(AksorDriftSignal.AKSOR_QUALITIES_ALL_NULL)).isEqualTo(1);
    }

    @Test
    void healthyBodyEmitsNoSignal() throws Exception {
        AksorDriftDetector d = new AksorDriftDetector();
        String body =
                "{\"qualities\":{\"q1080\":\"https://cdn.aksor.tv/x.mpd\",\"q720\":null,"
                        + "\"q480\":null,\"q360\":null,\"q2k\":null,\"q4k\":null}}";
        AksorApiClient.mapQualities(M.readTree(body), "abc", d);
        assertThat(d.snapshot().isClean()).isTrue();
    }
}
