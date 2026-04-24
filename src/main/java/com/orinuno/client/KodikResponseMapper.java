package com.orinuno.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.orinuno.client.dto.KodikMaterialDataDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.drift.DriftDetector;
import com.orinuno.drift.DriftRecord;
import com.orinuno.drift.DriftSamplingProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Thin facade over {@link DriftDetector} that adds Jackson-based deserialization for Kodik raw
 * responses. All drift-tracking state lives in the underlying detector; the legacy getters here
 * delegate to it for backwards-compatible callers (HealthController, tests).
 *
 * <p>Two constructors are kept on purpose: Spring uses {@link #KodikResponseMapper(DriftDetector)}
 * via {@code @Autowired}, while plain unit tests can do {@code new KodikResponseMapper()} and get a
 * default detector with {@link DriftSamplingProperties#defaults()}.
 */
@Slf4j
@Component
public class KodikResponseMapper {

    private final ObjectMapper objectMapper;
    private final DriftDetector driftDetector;

    public KodikResponseMapper() {
        this(new DriftDetector(DriftSamplingProperties.defaults()));
    }

    @Autowired
    public KodikResponseMapper(DriftDetector driftDetector) {
        this.driftDetector = driftDetector;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public DriftDetector getDriftDetector() {
        return driftDetector;
    }

    public Map<String, DriftRecord> getDetectedDrifts() {
        return driftDetector.getDetectedDrifts();
    }

    public AtomicInteger getTotalChecks() {
        return driftDetector.getTotalChecks();
    }

    public AtomicInteger getTotalDriftsDetected() {
        return driftDetector.getTotalDriftsDetected();
    }

    public <T> T mapAndDetectChanges(Map<String, Object> raw, Class<T> targetType) {
        detectSchemaChanges(raw, targetType);
        return objectMapper.convertValue(raw, targetType);
    }

    /**
     * Generic overload for {@link KodikReferenceResponse} payloads where the parameterised type
     * cannot be expressed as a raw {@link Class}. Drift detection runs on both envelope keys and
     * sampled items in {@code results} against {@code itemType}.
     */
    public <T> T mapAndDetectChanges(
            Map<String, Object> raw, TypeReference<T> targetType, Class<?> itemType) {
        detectReferenceSchemaChanges(raw, itemType);
        return objectMapper.convertValue(raw, targetType);
    }

    public void detectReferenceSchemaChanges(Map<String, Object> raw, Class<?> itemType) {
        String envelopeContext = "KodikReferenceResponse<" + itemType.getSimpleName() + ">";
        driftDetector.detectEnvelopeAndItems(
                raw,
                KodikReferenceResponse.class,
                itemType,
                envelopeContext,
                itemType.getSimpleName());
    }

    public void detectSchemaChanges(Map<String, Object> raw, Class<?> targetType) {
        Class<?> innerResult = findInnerResult(targetType);
        if (innerResult != null) {
            driftDetector.detectEnvelopeAndItems(
                    raw,
                    targetType,
                    innerResult,
                    targetType.getSimpleName(),
                    targetType.getSimpleName() + ".Result");
            sampleMaterialData(raw);
        } else {
            driftDetector.detect(raw, targetType, targetType.getSimpleName());
        }
    }

    /**
     * Kodik documents {@code material_data} as an open map — fields appear or disappear depending
     * on content type. We sample the same first-N items that the item-level detector walks and
     * record unknown {@code material_data} keys under a content-type-aware context label so a
     * drama-only field showing up in an anime result is easy to spot in the drift report.
     */
    private void sampleMaterialData(Map<String, Object> raw) {
        if (raw == null) return;
        Object results = raw.get("results");
        if (!(results instanceof List<?> list) || list.isEmpty()) return;
        int limit = driftDetector.sampleSize(list.size());
        for (int i = 0; i < limit; i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) continue;
            Object md = itemMap.get("material_data");
            if (!(md instanceof Map<?, ?> mdMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMd = (Map<String, Object>) mdMap;
            String contentType =
                    itemMap.get("type") == null ? "unknown" : String.valueOf(itemMap.get("type"));
            driftDetector.detect(
                    typedMd, KodikMaterialDataDto.class, "MaterialData[" + contentType + "]");
        }
    }

    private static Class<?> findInnerResult(Class<?> outer) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("Result")) {
                return inner;
            }
        }
        return null;
    }
}
