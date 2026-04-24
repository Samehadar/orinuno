package com.orinuno.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DriftDetector — generic schema drift detection")
class DriftDetectorTest {

    record SampleItem(String title, int count) {}

    record SampleEnvelope(String time, int total, List<SampleItem> results) {}

    @Nested
    @DisplayName("detect(rawMap, expectedType, contextLabel)")
    class DetectSingle {

        @Test
        @DisplayName("no drift when all keys match known fields")
        void noDriftKnownKeys() {
            DriftDetector detector = new DriftDetector();
            Map<String, Object> raw = Map.of("title", "X", "count", 1);

            detector.detect(raw, SampleItem.class, "SampleItem");

            assertThat(detector.getDetectedDrifts()).isEmpty();
            assertThat(detector.getTotalChecks().get()).isEqualTo(1);
            assertThat(detector.getTotalDriftsDetected().get()).isZero();
        }

        @Test
        @DisplayName("records drift on unknown key")
        void detectsUnknown() {
            DriftDetector detector = new DriftDetector();
            Map<String, Object> raw = Map.of("title", "X", "count", 1, "new_field", "v");

            detector.detect(raw, SampleItem.class, "SampleItem");

            assertThat(detector.getDetectedDrifts()).containsKey("SampleItem");
            assertThat(detector.getDetectedDrifts().get("SampleItem").unknownFields())
                    .containsExactly("new_field");
            assertThat(detector.getTotalDriftsDetected().get()).isEqualTo(1);
        }

        @Test
        @DisplayName("disabled detector is a no-op")
        void disabledIsNoOp() {
            DriftSamplingProperties cfg = new DriftSamplingProperties();
            cfg.setEnabled(false);
            DriftDetector detector = new DriftDetector(cfg);

            detector.detect(Map.of("foo", "bar"), SampleItem.class, "SampleItem");

            assertThat(detector.getDetectedDrifts()).isEmpty();
            assertThat(detector.getTotalChecks().get()).isZero();
        }
    }

    @Nested
    @DisplayName("detectEnvelopeAndItems(envelope, items)")
    class DetectFacade {

        @Test
        @DisplayName("no drift on clean envelope and items")
        void clean() {
            DriftDetector detector = new DriftDetector();
            Map<String, Object> raw =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            1,
                            "results",
                            List.of(Map.of("title", "X", "count", 1)));

            detector.detectEnvelopeAndItems(raw, SampleEnvelope.class, SampleItem.class);

            assertThat(detector.getDetectedDrifts()).isEmpty();
            assertThat(detector.getTotalChecks().get()).isEqualTo(1);
        }

        @Test
        @DisplayName("envelope drift only, items clean")
        void envelopeOnly() {
            DriftDetector detector = new DriftDetector();
            Map<String, Object> raw =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            0,
                            "results",
                            List.of(),
                            "extra_envelope_field",
                            "v");

            detector.detectEnvelopeAndItems(raw, SampleEnvelope.class, SampleItem.class);

            assertThat(detector.getDetectedDrifts()).containsOnlyKeys("SampleEnvelope");
            assertThat(detector.getDetectedDrifts().get("SampleEnvelope").unknownFields())
                    .containsExactly("extra_envelope_field");
        }

        @Test
        @DisplayName("explicit context labels are honoured")
        void customLabels() {
            DriftDetector detector = new DriftDetector();
            Map<String, Object> raw =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            1,
                            "results",
                            List.of(Map.of("title", "X", "count", 1, "weird", "v")));

            detector.detectEnvelopeAndItems(
                    raw, SampleEnvelope.class, SampleItem.class, "MyEnv<X>", "MyEnv<X>.Result");

            assertThat(detector.getDetectedDrifts()).containsOnlyKeys("MyEnv<X>.Result");
        }

        @Test
        @DisplayName("FIRST_N sampling inspects only the first N items")
        void firstNSampling() {
            DriftSamplingProperties cfg = new DriftSamplingProperties();
            cfg.getItemSampling().setMode(ItemSamplingMode.FIRST_N);
            cfg.getItemSampling().setLimit(1);
            DriftDetector detector = new DriftDetector(cfg);

            Map<String, Object> raw =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            2,
                            "results",
                            List.of(
                                    Map.of("title", "X", "count", 1),
                                    Map.of("title", "Y", "count", 2, "drama_only", "yes")));

            detector.detectEnvelopeAndItems(raw, SampleEnvelope.class, SampleItem.class);

            assertThat(detector.getDetectedDrifts()).isEmpty();
        }

        @Test
        @DisplayName("ALL sampling aggregates unknown keys across every item under one record")
        void allSamplingAggregates() {
            DriftSamplingProperties cfg = new DriftSamplingProperties();
            cfg.getItemSampling().setMode(ItemSamplingMode.ALL);
            DriftDetector detector = new DriftDetector(cfg);

            Map<String, Object> raw =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            2,
                            "results",
                            List.of(
                                    Map.of("title", "X", "count", 1, "field_a", "v"),
                                    Map.of("title", "Y", "count", 2, "field_b", "v")));

            detector.detectEnvelopeAndItems(raw, SampleEnvelope.class, SampleItem.class);

            assertThat(detector.getDetectedDrifts())
                    .containsOnlyKeys(SampleItem.class.getSimpleName());
            assertThat(detector.getDetectedDrifts().get("SampleItem").unknownFields())
                    .containsExactlyInAnyOrder("field_a", "field_b");
            assertThat(detector.getDetectedDrifts().get("SampleItem").hitCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("NONE sampling skips item-level checks even when items contain drift")
        void noneSamplingSkipsItems() {
            DriftSamplingProperties cfg = new DriftSamplingProperties();
            cfg.getItemSampling().setMode(ItemSamplingMode.NONE);
            DriftDetector detector = new DriftDetector(cfg);

            Map<String, Object> raw =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            1,
                            "results",
                            List.of(Map.of("title", "X", "count", 1, "rogue", "v")));

            detector.detectEnvelopeAndItems(raw, SampleEnvelope.class, SampleItem.class);

            assertThat(detector.getDetectedDrifts()).isEmpty();
        }

        @Test
        @DisplayName("envelopeShapeViolation when results is not a List")
        void envelopeShapeViolation() {
            DriftDetector detector = new DriftDetector();
            Object brokenResults = Map.of("oops", "object");
            Map<String, Object> raw = Map.of("time", "1ms", "total", 1, "results", brokenResults);

            detector.detectEnvelopeAndItems(raw, SampleEnvelope.class, SampleItem.class);

            assertThat(detector.getDetectedDrifts()).containsKey("envelopeShapeViolation");
            assertThat(detector.getDetectedDrifts().get("envelopeShapeViolation").unknownFields())
                    .containsExactly("results:" + brokenResults.getClass().getSimpleName());
        }

        @Test
        @DisplayName("hitCount accumulates and unknownFields union expands across calls")
        void hitCountAccumulates() {
            DriftDetector detector = new DriftDetector();
            Map<String, Object> first =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            1,
                            "results",
                            List.of(Map.of("title", "X", "count", 1, "field_a", "v")));
            Map<String, Object> second =
                    Map.of(
                            "time",
                            "1ms",
                            "total",
                            1,
                            "results",
                            List.of(Map.of("title", "Y", "count", 2, "field_b", "v")));

            detector.detectEnvelopeAndItems(first, SampleEnvelope.class, SampleItem.class);
            detector.detectEnvelopeAndItems(second, SampleEnvelope.class, SampleItem.class);

            DriftRecord rec = detector.getDetectedDrifts().get("SampleItem");
            assertThat(rec.hitCount()).isEqualTo(2);
            assertThat(rec.unknownFields()).containsExactlyInAnyOrder("field_a", "field_b");
        }
    }
}
