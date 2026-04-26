package com.orinuno.service;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.KodikListRequest;
import com.orinuno.drift.DriftDetector;
import com.orinuno.model.dto.KodikListItemView;
import com.orinuno.model.dto.KodikListPageView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Thin proxy over Kodik /list — exposes the minimal {@link KodikListItemView} subset that
 * parser-kodik needs for discovery (gap-fill, ongoing detection). Tracks whether schema drift was
 * observed during this call so the controller can surface a Warning header.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KodikListProxyService {

    private final KodikApiClient kodikApiClient;
    private final DriftDetector driftDetector;

    public Mono<ProxyPage> list(KodikListRequest request) {
        int driftBefore = driftDetector.getTotalDriftsDetected().get();
        return kodikApiClient
                .listRaw(request)
                .map(
                        raw -> {
                            int driftAfter = driftDetector.getTotalDriftsDetected().get();
                            boolean driftObserved = driftAfter > driftBefore;
                            return new ProxyPage(toView(raw), driftObserved);
                        });
    }

    private KodikListPageView toView(Map<String, Object> raw) {
        Object results = raw.get("results");
        List<KodikListItemView> items = new ArrayList<>();
        if (results instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    items.add(toItem(m));
                }
            }
        }
        Integer total = asInt(raw.get("total"));
        String nextPage = asString(raw.get("next_page"));
        String prevPage = asString(raw.get("prev_page"));
        return new KodikListPageView(items, total, nextPage, prevPage);
    }

    private KodikListItemView toItem(Map<?, ?> m) {
        Map<?, ?> material = m.get("material_data") instanceof Map<?, ?> md ? md : Map.of();
        return new KodikListItemView(
                asString(m.get("id")),
                asString(m.get("type")),
                asString(m.get("title")),
                asString(m.get("title_orig")),
                asString(m.get("other_title")),
                asInt(m.get("year")),
                asString(m.get("kinopoisk_id")),
                asString(m.get("imdb_id")),
                asString(m.get("shikimori_id")),
                pickPosterUrl(material),
                asInt(m.get("last_season")),
                asInt(m.get("last_episode")),
                asInt(m.get("episodes_count")),
                asString(material.get("anime_status")),
                asString(material.get("drama_status")),
                asString(material.get("all_status")),
                asString(m.get("quality")),
                asString(m.get("updated_at")));
    }

    private static String pickPosterUrl(Map<?, ?> material) {
        Object original = material.get("poster_url_original");
        if (original instanceof String s && !s.isBlank()) return s;
        Object regular = material.get("poster_url");
        if (regular instanceof String s && !s.isBlank()) return s;
        return null;
    }

    private static String asString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s.isBlank() ? null : s;
        return value.toString();
    }

    private static Integer asInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public record ProxyPage(KodikListPageView page, boolean schemaDriftObserved) {}
}
