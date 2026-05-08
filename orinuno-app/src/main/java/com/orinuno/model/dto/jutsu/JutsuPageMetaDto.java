package com.orinuno.model.dto.jutsu;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.orinuno.jutsu.episode.JutsuEpisodeMeta;
import com.orinuno.jutsu.episode.JutsuFilmMeta;
import com.orinuno.jutsu.episode.JutsuPageMeta;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Discriminated REST projection of {@link JutsuPageMeta}.
 *
 * <p>The {@code GET /api/v1/sources/jutsu/episode?url=…} endpoint accepts both episode and
 * full-length-film URLs; the response shape carries a {@code kind} discriminator so consumers can
 * pattern-match without re-parsing the URL. {@code kind=episode} → {@link JutsuEpisodeMetaDto} with
 * season/episode fields; {@code kind=film} → {@link JutsuFilmMetaDto} with a single {@code
 * filmIndex}.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = JutsuEpisodeMetaDto.class, name = "episode"),
    @JsonSubTypes.Type(value = JutsuFilmMetaDto.class, name = "film"),
})
@Schema(
        description =
                "Lightweight metadata for one jut.su viewer page. The `kind` discriminator selects"
                        + " between an episode (`season`, `episode`) and a full-length film"
                        + " (`filmIndex`).",
        oneOf = {JutsuEpisodeMetaDto.class, JutsuFilmMetaDto.class},
        discriminatorProperty = "kind",
        discriminatorMapping = {
            @DiscriminatorMapping(value = "episode", schema = JutsuEpisodeMetaDto.class),
            @DiscriminatorMapping(value = "film", schema = JutsuFilmMetaDto.class)
        })
public sealed interface JutsuPageMetaDto permits JutsuEpisodeMetaDto, JutsuFilmMetaDto {

    /**
     * The wire-format discriminator emitted on serialization. Frontend consumers should switch on
     * this string before reading kind-specific fields.
     */
    String kind();

    static JutsuPageMetaDto from(JutsuPageMeta page) {
        if (page instanceof JutsuEpisodeMeta episode) {
            return JutsuEpisodeMetaDto.from(episode);
        }
        if (page instanceof JutsuFilmMeta film) {
            return JutsuFilmMetaDto.from(film);
        }
        throw new IllegalStateException(
                "Unknown JutsuPageMeta runtime type: " + page.getClass().getName());
    }
}
