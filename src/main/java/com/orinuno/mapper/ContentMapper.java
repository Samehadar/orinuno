package com.orinuno.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.dto.ContentDto;
import com.orinuno.model.dto.ContentExportDto;
import com.orinuno.model.dto.EpisodeVariantDto;
import java.util.*;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class ContentMapper {

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
                parseScreenshots(content.getScreenshots()),
                content.getCamrip(),
                content.getLgbt(),
                seasons);
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
            log.warn("⚠️ Failed to parse screenshots JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
