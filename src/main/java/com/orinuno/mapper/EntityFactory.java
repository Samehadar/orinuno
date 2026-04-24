package com.orinuno.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class EntityFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public KodikContent createContent(KodikSearchResponse.Result result) {
        Map<String, Object> md = result.getMaterialData();
        return KodikContent.builder()
                .kodikId(result.getId())
                .type(result.getType())
                .title(result.getTitle())
                .titleOrig(result.getTitleOrig())
                .otherTitle(result.getOtherTitle())
                .year(result.getYear())
                .kinopoiskId(result.getKinopoiskId())
                .imdbId(result.getImdbId())
                .shikimoriId(result.getShikimoriId())
                .worldartLink(result.getWorldartLink())
                .screenshots(serializeScreenshots(result.getScreenshots()))
                .materialData(serializeJson(md))
                .kinopoiskRating(extractDouble(md, "kinopoisk_rating"))
                .imdbRating(extractDouble(md, "imdb_rating"))
                .shikimoriRating(extractDouble(md, "shikimori_rating"))
                .genres(extractCommaSeparated(md, "genres"))
                .blockedCountries(extractCommaSeparated(md, "blocked_countries"))
                .camrip(result.getCamrip())
                .lgbt(result.getLgbt())
                .lastSeason(result.getLastSeason())
                .lastEpisode(result.getLastEpisode())
                .episodesCount(result.getEpisodesCount())
                .quality(result.getQuality())
                .build();
    }

    public List<KodikEpisodeVariant> createVariants(
            Long contentId, KodikSearchResponse.Result result) {
        List<KodikEpisodeVariant> variants = new ArrayList<>();

        if (result.getSeasons() != null) {
            for (Map.Entry<String, KodikSearchResponse.Season> seasonEntry :
                    result.getSeasons().entrySet()) {
                int seasonNum = parseIntSafe(seasonEntry.getKey(), 0);
                KodikSearchResponse.Season season = seasonEntry.getValue();

                if (season.getEpisodes() != null) {
                    for (Map.Entry<String, String> episodeEntry : season.getEpisodes().entrySet()) {
                        int episodeNum = parseIntSafe(episodeEntry.getKey(), 0);
                        variants.add(
                                buildVariant(
                                        contentId,
                                        seasonNum,
                                        episodeNum,
                                        episodeEntry.getValue(),
                                        result));
                    }
                }
            }
        } else {
            // Film or single content -- season 0, episode 0
            variants.add(buildVariant(contentId, 0, 0, result.getLink(), result));
        }

        return variants;
    }

    private KodikEpisodeVariant buildVariant(
            Long contentId,
            int season,
            int episode,
            String kodikLink,
            KodikSearchResponse.Result result) {
        KodikSearchResponse.Translation translation = result.getTranslation();
        return KodikEpisodeVariant.builder()
                .contentId(contentId)
                .seasonNumber(season)
                .episodeNumber(episode)
                .translationId(translation != null ? translation.getId() : 0)
                .translationTitle(translation != null ? translation.getTitle() : null)
                .translationType(translation != null ? translation.getType() : null)
                .quality(result.getQuality())
                .kodikLink(kodikLink)
                .build();
    }

    private String serializeJson(Object obj) {
        if (obj == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize JSON: {}", e.getMessage());
            return null;
        }
    }

    private Double extractDouble(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number num) return num.doubleValue();
        if (val instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String extractCommaSeparated(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Collection<?> col) {
            return col.stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(","));
        }
        if (val instanceof String str) return str;
        return null;
    }

    private String serializeScreenshots(List<String> screenshots) {
        if (screenshots == null || screenshots.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(screenshots);
        } catch (JsonProcessingException e) {
            log.warn("⚠️ Failed to serialize screenshots: {}", e.getMessage());
            return null;
        }
    }

    private int parseIntSafe(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
