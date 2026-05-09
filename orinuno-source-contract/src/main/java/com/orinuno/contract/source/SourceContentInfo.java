package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Title-level metadata observed from an upstream source: localised titles, year, genre tags,
 * external ids, the source-level kind hint. Mirrors meter's {@code ContentCommonInfo} (see {@code
 * external meter-api starter/.../ContentCommonInfo.java}) but stripped of
 * consumer-specific value-object wrappers and closed enums.
 *
 * <p>What's intentionally <em>not</em> here: media URLs (those live on {@link
 * SourceEpisodeVariant}), audio/video quality enums (kept as plain strings on the variant when
 * relevant), poster/trailer URLs (out of scope for ARCH-0017's first cut — added later if a
 * consumer needs them; meter's {@code posterFilepath} family was consumer-specific anyway).
 *
 * <p>{@code titleRu} and {@code titleEn} are the source's best-effort localisation. jut.su's
 * primary {@code title} field is always Russian; Kodik exposes both. Consumers must treat blanks as
 * {@code null} (see {@link Builder}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceContentInfo(
        @Nullable String titleRu,
        @Nullable String titleEn,
        @Nullable Integer year,
        @Nonnull ContentKindHint kindHint,
        @Nonnull ExternalIds externalIds) {

    public SourceContentInfo {
        Objects.requireNonNull(kindHint, "kindHint");
        Objects.requireNonNull(externalIds, "externalIds");
        titleRu = blankToNull(titleRu);
        titleEn = blankToNull(titleEn);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {
        @Nullable private String titleRu;
        @Nullable private String titleEn;
        @Nullable private Integer year;
        private ContentKindHint kindHint = ContentKindHint.UNKNOWN;
        private ExternalIds externalIds = ExternalIds.empty();

        private Builder() {}

        public Builder titleRu(@Nullable String value) {
            this.titleRu = value;
            return this;
        }

        public Builder titleEn(@Nullable String value) {
            this.titleEn = value;
            return this;
        }

        public Builder year(@Nullable Integer value) {
            this.year = value;
            return this;
        }

        public Builder kindHint(ContentKindHint value) {
            this.kindHint = Objects.requireNonNull(value, "kindHint");
            return this;
        }

        public Builder externalIds(ExternalIds value) {
            this.externalIds = Objects.requireNonNull(value, "externalIds");
            return this;
        }

        public SourceContentInfo build() {
            return new SourceContentInfo(titleRu, titleEn, year, kindHint, externalIds);
        }
    }
}
