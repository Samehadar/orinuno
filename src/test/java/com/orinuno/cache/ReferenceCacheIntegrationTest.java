package com.orinuno.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.configuration.ReferenceCacheConfig;
import com.orinuno.service.ReferenceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import reactor.core.publisher.Mono;

/**
 * Contract tests for the reference cache toggle. Validates that Caffeine is wired when enabled,
 * that NoOp manager replaces it when disabled, and that the blocking {@link ReferenceService}
 * facade honours both modes end-to-end.
 */
@DisplayName("Reference cache toggle — enabled/disabled wiring and behaviour")
class ReferenceCacheIntegrationTest {

    @Configuration
    @EnableConfigurationProperties(OrinunoProperties.class)
    @Import({ReferenceCacheConfig.class, ReferenceService.class})
    static class Ctx {

        @Bean
        KodikApiClient kodikApiClient() {
            return mock(KodikApiClient.class);
        }
    }

    @Nested
    @SpringJUnitConfig(Ctx.class)
    @TestPropertySource(
            properties = {
                "orinuno.cache.reference.enabled=true",
                "orinuno.cache.reference.ttl-seconds=3600"
            })
    @DisplayName("enabled → Caffeine caches repeated reads")
    class WhenEnabled {

        @Autowired KodikApiClient kodikApiClient;
        @Autowired ReferenceService referenceService;
        @Autowired CacheManager cacheManager;

        @BeforeEach
        void resetMocks() {
            reset(kodikApiClient);
            cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        }

        @Test
        @DisplayName("second call is served from cache (no extra upstream hit)")
        void secondCallHitsCache() {
            KodikReferenceResponse<KodikTranslationDto> payload =
                    KodikReferenceResponse.<KodikTranslationDto>builder()
                            .time("1ms")
                            .total(1)
                            .results(List.of(new KodikTranslationDto(610, "AniLibria", 42)))
                            .build();
            when(kodikApiClient.translations()).thenReturn(Mono.just(payload));

            KodikReferenceResponse<KodikTranslationDto> first = referenceService.translations();
            KodikReferenceResponse<KodikTranslationDto> second = referenceService.translations();

            assertThat(first).isSameAs(second);
            verify(kodikApiClient, times(1)).translations();
        }

        @Test
        @DisplayName("CacheManager is Caffeine-backed and exposes all five cache regions")
        void cacheManagerIsCaffeine() {
            assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
            assertThat(cacheManager.getCacheNames())
                    .contains(
                            ReferenceCacheConfig.TRANSLATIONS,
                            ReferenceCacheConfig.GENRES,
                            ReferenceCacheConfig.COUNTRIES,
                            ReferenceCacheConfig.YEARS,
                            ReferenceCacheConfig.QUALITIES);
        }
    }

    @Nested
    @SpringJUnitConfig(Ctx.class)
    @TestPropertySource(properties = "orinuno.cache.reference.enabled=false")
    @DisplayName("disabled → every read goes upstream")
    class WhenDisabled {

        @Autowired KodikApiClient kodikApiClient;
        @Autowired ReferenceService referenceService;
        @Autowired CacheManager cacheManager;

        @BeforeEach
        void resetMocks() {
            reset(kodikApiClient);
        }

        @Test
        @DisplayName("NoOpCacheManager is installed when toggle is off")
        void cacheManagerIsNoOp() {
            assertThat(cacheManager).isInstanceOf(NoOpCacheManager.class);
        }

        @Test
        @DisplayName("each call reaches KodikApiClient")
        void everyCallHitsUpstream() {
            KodikReferenceResponse<KodikGenreDto> payload =
                    KodikReferenceResponse.<KodikGenreDto>builder()
                            .time("1ms")
                            .total(1)
                            .results(List.of(new KodikGenreDto("anime", 10)))
                            .build();
            when(kodikApiClient.genres()).thenReturn(Mono.just(payload));

            referenceService.genres();
            referenceService.genres();
            referenceService.genres();

            verify(kodikApiClient, times(3)).genres();
        }
    }
}
