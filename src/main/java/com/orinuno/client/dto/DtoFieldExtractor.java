package com.orinuno.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DtoFieldExtractor {

    private static final java.util.Map<Class<?>, Set<String>> CACHE = new ConcurrentHashMap<>();

    private DtoFieldExtractor() {}

    public static Set<String> knownJsonFields(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, DtoFieldExtractor::extract);
    }

    private static Set<String> extract(Class<?> clazz) {
        Set<String> fields = new LinkedHashSet<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (field.isAnnotationPresent(JsonIgnore.class)) continue;

            JsonProperty jp = field.getAnnotation(JsonProperty.class);
            if (jp != null && !jp.value().isEmpty()) {
                fields.add(jp.value());
            } else {
                fields.add(toSnakeCase(field.getName()));
            }

            JsonAlias aliases = field.getAnnotation(JsonAlias.class);
            if (aliases != null) {
                Collections.addAll(fields, aliases.value());
            }
        }
        return Collections.unmodifiableSet(fields);
    }

    static String toSnakeCase(String camelCase) {
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
