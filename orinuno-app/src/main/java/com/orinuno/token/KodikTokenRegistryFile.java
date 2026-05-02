package com.orinuno.token;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * On-disk representation of {@code data/kodik_tokens.json}. Exactly mirrors AnimeParsers' {@code
 * kdk_tokns/tokens.json} shape: one list per tier, at the top level.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class KodikTokenRegistryFile {

    @Builder.Default private List<KodikTokenEntry> stable = new ArrayList<>();
    @Builder.Default private List<KodikTokenEntry> unstable = new ArrayList<>();
    @Builder.Default private List<KodikTokenEntry> legacy = new ArrayList<>();
    @Builder.Default private List<KodikTokenEntry> dead = new ArrayList<>();

    public static KodikTokenRegistryFile empty() {
        return builder().build();
    }

    public List<KodikTokenEntry> bucket(KodikTokenTier tier) {
        return switch (tier) {
            case STABLE -> stable;
            case UNSTABLE -> unstable;
            case LEGACY -> legacy;
            case DEAD -> dead;
        };
    }
}
