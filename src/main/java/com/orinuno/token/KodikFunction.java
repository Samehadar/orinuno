package com.orinuno.token;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Functions a Kodik token can be authorised for. Naming mirrors AnimeParsers' {@code
 * functions_availability} keys so the registry JSON stays byte-compatible with the reference
 * implementation.
 */
public enum KodikFunction {
    BASE_SEARCH("base_search"),
    BASE_SEARCH_BY_ID("base_search_by_id"),
    GET_LIST("get_list"),
    SEARCH("search"),
    SEARCH_BY_ID("search_by_id"),
    GET_INFO("get_info"),
    GET_LINK("get_link"),
    GET_M3U8_PLAYLIST_LINK("get_m3u8_playlist_link");

    private static final Map<String, KodikFunction> BY_JSON_KEY =
            Map.copyOf(
                    java.util.Arrays.stream(values())
                            .collect(Collectors.toMap(KodikFunction::getJsonKey, f -> f)));

    private final String jsonKey;

    KodikFunction(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    @JsonValue
    public String getJsonKey() {
        return jsonKey;
    }

    @JsonCreator
    public static KodikFunction fromJsonKey(String key) {
        KodikFunction value = BY_JSON_KEY.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Unknown Kodik function key: " + key);
        }
        return value;
    }
}
