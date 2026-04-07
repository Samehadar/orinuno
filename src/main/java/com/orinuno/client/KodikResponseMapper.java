package com.orinuno.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.annotation.JsonProperty;

@Slf4j
@Component
public class KodikResponseMapper {

    private final ObjectMapper objectMapper;
    private final Map<Class<?>, Set<String>> knownFieldsCache = new ConcurrentHashMap<>();

    @Getter
    private final Map<String, DriftRecord> detectedDrifts = new ConcurrentHashMap<>();
    @Getter
    private final AtomicInteger totalChecks = new AtomicInteger(0);
    @Getter
    private final AtomicInteger totalDriftsDetected = new AtomicInteger(0);

    public record DriftRecord(
            Set<String> unknownFields,
            Instant firstSeen,
            Instant lastSeen,
            int hitCount
    ) {}

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
        Set<String> known = knownFieldsCache.computeIfAbsent(targetType, this::extractKnownFields);
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : raw.keySet()) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("Kodik API schema drift: unknown fields {} in {}", unknown, targetType.getSimpleName());
            recordDrift(targetType.getSimpleName(), unknown);
        }

        Object results = raw.get("results");
        if (results instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> firstMap) {
                for (Class<?> inner : targetType.getDeclaredClasses()) {
                    if (inner.getSimpleName().equals("Result")) {
                        Set<String> knownInner = knownFieldsCache.computeIfAbsent(inner, this::extractKnownFields);
                        Set<String> unknownInner = new LinkedHashSet<>();
                        for (Object k : firstMap.keySet()) {
                            if (!knownInner.contains(k.toString())) {
                                unknownInner.add(k.toString());
                            }
                        }
                        if (!unknownInner.isEmpty()) {
                            log.warn("Kodik API schema drift: unknown fields {} in {}.Result", unknownInner, targetType.getSimpleName());
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
        detectedDrifts.merge(context,
                new DriftRecord(new LinkedHashSet<>(unknownFields), now, now, 1),
                (existing, incoming) -> {
                    Set<String> merged = new LinkedHashSet<>(existing.unknownFields());
                    merged.addAll(incoming.unknownFields());
                    return new DriftRecord(merged, existing.firstSeen(), now, existing.hitCount() + 1);
                });
    }

    private Set<String> extractKnownFields(Class<?> clazz) {
        Set<String> fields = new HashSet<>();
        for (Field field : clazz.getDeclaredFields()) {
            JsonProperty ann = field.getAnnotation(JsonProperty.class);
            if (ann != null && !ann.value().isEmpty()) {
                fields.add(ann.value());
            } else {
                // Convert camelCase to snake_case
                fields.add(toSnakeCase(field.getName()));
            }
        }
        return fields;
    }

    private static String toSnakeCase(String camelCase) {
        StringBuilder sb = new StringBuilder();
        for (char c : camelCase.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (!sb.isEmpty()) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
