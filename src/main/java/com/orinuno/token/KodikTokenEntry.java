package com.orinuno.token;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single Kodik token with its observed per-function availability matrix. Serialised into {@code
 * data/kodik_tokens.json} inside a tier bucket (not stored with the tier field — the tier is
 * implied by its position in the registry JSON).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KodikTokenEntry {

    @JsonProperty("value")
    private String value;

    /**
     * Snake-case map matching AnimeParsers' {@code functions_availability}. Stored as {@code
     * Map<String, Boolean>} (not {@code EnumMap<KodikFunction, Boolean>}) so forward-compatibility
     * with unknown keys is preserved, similarly to how {@link
     * com.orinuno.client.KodikResponseMapper} tolerates unknown fields.
     */
    @JsonProperty("functions_availability")
    @Builder.Default
    private Map<String, Boolean> functionsAvailability = new LinkedHashMap<>();

    @JsonProperty("last_checked")
    private Instant lastChecked;

    @JsonProperty("note")
    private String note;

    @JsonIgnore
    public boolean isAvailableFor(KodikFunction function) {
        if (functionsAvailability == null) {
            return false;
        }
        Boolean flag = functionsAvailability.get(function.getJsonKey());
        return Boolean.TRUE.equals(flag);
    }

    public void setAvailability(KodikFunction function, boolean available) {
        if (functionsAvailability == null) {
            functionsAvailability = new LinkedHashMap<>();
        }
        functionsAvailability.put(function.getJsonKey(), available);
    }

    /** Returns the availability matrix as a typed enum map. Useful for metrics and diagnostics. */
    @JsonIgnore
    public EnumMap<KodikFunction, Boolean> availabilitySnapshot() {
        EnumMap<KodikFunction, Boolean> result = new EnumMap<>(KodikFunction.class);
        if (functionsAvailability == null) {
            return result;
        }
        for (KodikFunction function : KodikFunction.values()) {
            Boolean flag = functionsAvailability.get(function.getJsonKey());
            result.put(function, Boolean.TRUE.equals(flag));
        }
        return result;
    }

    /** Returns true if every function is marked false. */
    @JsonIgnore
    public boolean isAllDead() {
        if (functionsAvailability == null || functionsAvailability.isEmpty()) {
            return false;
        }
        for (KodikFunction function : KodikFunction.values()) {
            if (Boolean.TRUE.equals(functionsAvailability.get(function.getJsonKey()))) {
                return false;
            }
        }
        return true;
    }
}
