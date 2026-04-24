package com.orinuno.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.orinuno.client.dto.DtoFieldExtractor;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KodikResponseMapper {

    private final ObjectMapper objectMapper;

    @Getter private final Map<String, DriftRecord> detectedDrifts = new ConcurrentHashMap<>();
    @Getter private final AtomicInteger totalChecks = new AtomicInteger(0);
    @Getter private final AtomicInteger totalDriftsDetected = new AtomicInteger(0);

    public record DriftRecord(
            Set<String> unknownFields, Instant firstSeen, Instant lastSeen, int hitCount) {}

    public KodikResponseMapper() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public <T> T mapAndDetectChanges(Map<String, Object> raw, Class<T> targetType) {
        detectSchemaChanges(raw, targetType);
        return objectMapper.convertValue(raw, targetType);
    }

    public void detectSchemaChanges(Map<String, Object> raw, Class<?> targetType) {
        totalChecks.incrementAndGet();
        Set<String> known = DtoFieldExtractor.knownJsonFields(targetType);
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : raw.keySet()) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn(
                    "Kodik API schema drift: unknown fields {} in {}",
                    unknown,
                    targetType.getSimpleName());
            recordDrift(targetType.getSimpleName(), unknown);
        }

        Object results = raw.get("results");
        if (results instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> firstMap) {
                for (Class<?> inner : targetType.getDeclaredClasses()) {
                    if (inner.getSimpleName().equals("Result")) {
                        Set<String> knownInner = DtoFieldExtractor.knownJsonFields(inner);
                        Set<String> unknownInner = new LinkedHashSet<>();
                        for (Object k : firstMap.keySet()) {
                            if (!knownInner.contains(k.toString())) {
                                unknownInner.add(k.toString());
                            }
                        }
                        if (!unknownInner.isEmpty()) {
                            log.warn(
                                    "Kodik API schema drift: unknown fields {} in {}.Result",
                                    unknownInner,
                                    targetType.getSimpleName());
                            recordDrift(targetType.getSimpleName() + ".Result", unknownInner);
                        }
                        break;
                    }
                }
            }
        }
    }

    private void recordDrift(String context, Set<String> unknownFields) {
        totalDriftsDetected.incrementAndGet();
        Instant now = Instant.now();
        detectedDrifts.merge(
                context,
                new DriftRecord(new LinkedHashSet<>(unknownFields), now, now, 1),
                (existing, incoming) -> {
                    Set<String> merged = new LinkedHashSet<>(existing.unknownFields());
                    merged.addAll(incoming.unknownFields());
                    return new DriftRecord(
                            merged, existing.firstSeen(), now, existing.hitCount() + 1);
                });
    }
}
