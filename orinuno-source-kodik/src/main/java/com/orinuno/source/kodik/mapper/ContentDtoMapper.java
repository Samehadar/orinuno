/*
 * ContentDtoMapper — ADR 0021 §C1.1.
 *
 * Read-only mapper for the source-kodik /api/v1/content/* surface. Mirrors
 * the read half of orinuno-app's ContentMapper (the toDto overloads + the
 * JSON-string helpers). The export half (ContentMapper.toExportDto +
 * poster/status extraction) stays in orinuno-app for now and moves over
 * in ADR 0021 §C4.1.
 */
package com.orinuno.source.kodik.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.source.kodik.model.KodikContent;
import com.orinuno.source.kodik.model.KodikEpisodeVariant;
import com.orinuno.source.kodik.model.dto.ContentDto;
import com.orinuno.source.kodik.model.dto.ContentExportDto;
import com.orinuno.source.kodik.model.dto.EpisodeVariantDto;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class ContentDtoMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ContentDto toDto(KodikContent entity) {
        return ContentDto.builder()
                .id(entity.getId())
                .kodikId(entity.getKodikId())
                .type(entity.getType())
                .title(entity.getTitle())
                .titleOrig(entity.getTitleOrig())
                .otherTitle(entity.getOtherTitle())
                .year(entity.getYear())
                .kinopoiskId(entity.getKinopoiskId())
                .imdbId(entity.getImdbId())
                .shikimoriId(entity.getShikimoriId())
                .worldartLink(entity.getWorldartLink())
                .screenshots(parseScreenshots(entity.getScreenshots()))
                .materialData(parseMaterialData(entity.getMaterialData()))
                .kinopoiskRating(entity.getKinopoiskRating())
                .imdbRating(entity.getImdbRating())
                .shikimoriRating(entity.getShikimoriRating())
                .genres(entity.getGenres())
                .blockedCountries(entity.getBlockedCountries())
                .camrip(entity.getCamrip())
                .lgbt(entity.getLgbt())
                .lastSeason(entity.getLastSeason())
                .lastEpisode(entity.getLastEpisode())
                .episodesCount(entity.getEpisodesCount())
                .quality(entity.getQuality())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Denormalised export DTO: groups variants by season → episode → variant under a
     * Kodik-derived chrome envelope (poster URL + status flags). Ported from orinuno-app's
     * ContentMapper.toExportDto (ADR 0021 §C4.1). Caller supplies the pre-filtered list of
     * variants — typically {@code findByContentIdWithDecodedVideo} so the response only
     * contains variants with a populated episode_video row.
     */
    public ContentExportDto toExportDto(KodikContent content, List<KodikEpisodeVariant> variants) {
        Map<Integer, Map<Integer, List<KodikEpisodeVariant>>> grouped =
                variants.stream()
                        .collect(
                                Collectors.groupingBy(
                                        KodikEpisodeVariant::getSeasonNumber,
                                        Collectors.groupingBy(
                                                KodikEpisodeVariant::getEpisodeNumber)));

        List<ContentExportDto.SeasonExportDto> seasons =
                grouped.entrySet().stream()
                        .sorted(Comparator.comparingInt(Map.Entry::getKey))
                        .map(
                                seasonEntry -> {
                                    List<ContentExportDto.EpisodeExportDto> episodes =
                                            seasonEntry.getValue().entrySet().stream()
                                                    .sorted(
                                                            Comparator.comparingInt(
                                                                    Map.Entry::getKey))
                                                    .map(
                                                            episodeEntry ->
                                                                    new ContentExportDto
                                                                            .EpisodeExportDto(
                                                                            episodeEntry.getKey(),
                                                                            episodeEntry
                                                                                    .getValue()
                                                                                    .stream()
                                                                                    .map(
                                                                                            v ->
                                                                                                    new ContentExportDto
                                                                                                            .VariantExportDto(
                                                                                                            v
                                                                                                                    .getId(),
                                                                                                            v
                                                                                                                    .getTranslationId(),
                                                                                                            v
                                                                                                                    .getTranslationTitle(),
                                                                                                            v
                                                                                                                    .getTranslationType(),
                                                                                                            v
                                                                                                                    .getQuality(),
                                                                                                            v
                                                                                                                    .getMp4Link()))
                                                                                    .toList()))
                                                    .toList();
                                    return new ContentExportDto.SeasonExportDto(
                                            seasonEntry.getKey(), episodes);
                                })
                        .toList();

        Map<String, Object> materialData = parseMaterialData(content.getMaterialData());
        String posterUrl = extractPosterUrl(materialData);
        String animeStatus = extractStringField(materialData, "anime_status");
        String dramaStatus = extractStringField(materialData, "drama_status");
        String allStatus = extractStringField(materialData, "all_status");
        Boolean ongoing = deriveOngoing(animeStatus, dramaStatus, allStatus);

        return new ContentExportDto(
                content.getId(),
                content.getType(),
                content.getTitle(),
                content.getTitleOrig(),
                content.getOtherTitle(),
                content.getYear(),
                content.getKinopoiskId(),
                content.getImdbId(),
                content.getShikimoriId(),
                posterUrl,
                parseScreenshots(content.getScreenshots()),
                content.getCamrip(),
                content.getLgbt(),
                content.getLastSeason(),
                content.getLastEpisode(),
                content.getEpisodesCount(),
                animeStatus,
                dramaStatus,
                allStatus,
                ongoing,
                seasons);
    }

    private String extractStringField(Map<String, Object> materialData, String key) {
        if (materialData == null) return null;
        Object value = materialData.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private Boolean deriveOngoing(String animeStatus, String dramaStatus, String allStatus) {
        if (animeStatus == null && dramaStatus == null && allStatus == null) {
            return null;
        }
        return isOngoingValue(animeStatus)
                || isOngoingValue(dramaStatus)
                || isOngoingValue(allStatus);
    }

    private boolean isOngoingValue(String status) {
        if (status == null) return false;
        String normalised = status.toLowerCase();
        return normalised.contains("ongoing")
                || normalised.contains("airing")
                || normalised.contains("releasing")
                || normalised.contains("currently")
                || normalised.equals("anons");
    }

    private String extractPosterUrl(Map<String, Object> materialData) {
        if (materialData == null) {
            return null;
        }
        Object original = materialData.get("poster_url_original");
        if (original instanceof String s && !s.isBlank()) {
            return s;
        }
        Object regular = materialData.get("poster_url");
        if (regular instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    public EpisodeVariantDto toDto(KodikEpisodeVariant entity) {
        return EpisodeVariantDto.builder()
                .id(entity.getId())
                .contentId(entity.getContentId())
                .seasonNumber(entity.getSeasonNumber())
                .episodeNumber(entity.getEpisodeNumber())
                .translationId(entity.getTranslationId())
                .translationTitle(entity.getTranslationTitle())
                .translationType(entity.getTranslationType())
                .quality(entity.getQuality())
                .kodikLink(entity.getKodikLink())
                .mp4Link(entity.getMp4Link())
                .localFilepath(entity.getLocalFilepath())
                .build();
    }

    private Map<String, Object> parseMaterialData(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse material_data JSON: {}", e.getMessage());
            return null;
        }
    }

    private List<String> parseScreenshots(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse screenshots JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
