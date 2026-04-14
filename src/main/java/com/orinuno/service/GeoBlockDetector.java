package com.orinuno.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GeoBlockDetector {

    private static final Pattern GEO_BLOCKED_CDN_PATTERN = Pattern.compile("/s/m/[^/]+/");

    public boolean isCdnGeoBlocked(String cdnUrl) {
        if (cdnUrl == null) return false;
        boolean blocked = GEO_BLOCKED_CDN_PATTERN.matcher(cdnUrl).find();
        if (blocked) {
            log.warn("CDN geo-block detected in URL: {}", cdnUrl.substring(0, Math.min(cdnUrl.length(), 80)));
        }
        return blocked;
    }

    public List<String> extractBlockedCountries(Map<String, Object> materialData) {
        if (materialData == null) return List.of();
        Object val = materialData.get("blocked_countries");
        if (val instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    public boolean isBlockedInCountry(Map<String, Object> materialData, String countryCode) {
        if (materialData == null || countryCode == null) return false;
        return extractBlockedCountries(materialData).stream()
                .anyMatch(c -> c.equalsIgnoreCase(countryCode));
    }
}
