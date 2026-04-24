package com.orinuno.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.drift.DriftDetector;
import com.orinuno.drift.DriftRecord;
import com.orinuno.drift.DriftSamplingProperties;
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
        } else {
            driftDetector.detect(raw, targetType, targetType.getSimpleName());
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
