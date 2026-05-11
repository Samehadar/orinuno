package com.orinuno.source.kodik.service;

import com.kodik.client.KodikApiClient;
import com.kodik.client.dto.reference.KodikCountryDto;
import com.kodik.client.dto.reference.KodikGenreDto;
import com.kodik.client.dto.reference.KodikQualityDto;
import com.kodik.client.dto.reference.KodikReferenceResponse;
import com.kodik.client.dto.reference.KodikTranslationDto;
import com.kodik.client.dto.reference.KodikYearDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cache-aware facade over {@link KodikApiClient} reference endpoints. The methods are blocking on
 * purpose — Spring's {@link org.springframework.cache.annotation.Cacheable @Cacheable} does not
 * unwrap reactive publishers, and the traffic on these endpoints (five per-deployment warm-ups per
 * TTL) does not justify a bespoke reactive cache layer. Controllers are expected to bridge back to
 * reactive via {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceService {

    private final KodikApiClient kodikApiClient;

    @Cacheable(cacheNames = "kodik-translations", sync = true)
    public KodikReferenceResponse<KodikTranslationDto> translations() {
        log.debug("Reference cache miss: /translations");
        return kodikApiClient.translations().block();
    }

    @Cacheable(cacheNames = "kodik-genres", sync = true)
    public KodikReferenceResponse<KodikGenreDto> genres() {
        log.debug("Reference cache miss: /genres");
        return kodikApiClient.genres().block();
    }

    @Cacheable(cacheNames = "kodik-countries", sync = true)
    public KodikReferenceResponse<KodikCountryDto> countries() {
        log.debug("Reference cache miss: /countries");
        return kodikApiClient.countries().block();
    }

    @Cacheable(cacheNames = "kodik-years", sync = true)
    public KodikReferenceResponse<KodikYearDto> years() {
        log.debug("Reference cache miss: /years");
        return kodikApiClient.years().block();
    }

    @Cacheable(cacheNames = "kodik-qualities", sync = true)
    public KodikReferenceResponse<KodikQualityDto> qualities() {
        log.debug("Reference cache miss: /qualities");
        return kodikApiClient.qualities().block();
    }
}
