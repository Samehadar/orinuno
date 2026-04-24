package com.orinuno.controller;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.reference.KodikCountryDto;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikQualityDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import com.orinuno.service.ReferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Exposes Kodik reference endpoints ({@code /translations}, {@code /genres}, {@code /countries},
 * {@code /years}, {@code /qualities}) as typed REST responses. Values are cached per {@link
 * com.orinuno.configuration.ReferenceCacheConfig}; clients can force a fresh upstream call with
 * {@code ?fresh=true}, which bypasses the cache layer entirely without invalidating previously
 * cached entries.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reference")
@RequiredArgsConstructor
@Tag(name = "Reference", description = "Typed Kodik reference dictionaries (cached)")
public class ReferenceController {

    private final ReferenceService referenceService;
    private final KodikApiClient kodikApiClient;

    @GetMapping("/translations")
    @Operation(summary = "List translation authors (cached)")
    public Mono<ResponseEntity<KodikReferenceResponse<KodikTranslationDto>>> translations(
            @Parameter(description = "Skip cache and hit Kodik directly")
                    @RequestParam(defaultValue = "false")
                    boolean fresh) {
        return serve(fresh, referenceService::translations, kodikApiClient::translations);
    }

    @GetMapping("/genres")
    @Operation(summary = "List genres (cached)")
    public Mono<ResponseEntity<KodikReferenceResponse<KodikGenreDto>>> genres(
            @RequestParam(defaultValue = "false") boolean fresh) {
        return serve(fresh, referenceService::genres, kodikApiClient::genres);
    }

    @GetMapping("/countries")
    @Operation(summary = "List countries (cached)")
    public Mono<ResponseEntity<KodikReferenceResponse<KodikCountryDto>>> countries(
            @RequestParam(defaultValue = "false") boolean fresh) {
        return serve(fresh, referenceService::countries, kodikApiClient::countries);
    }

    @GetMapping("/years")
    @Operation(summary = "List years with content counts (cached)")
    public Mono<ResponseEntity<KodikReferenceResponse<KodikYearDto>>> years(
            @RequestParam(defaultValue = "false") boolean fresh) {
        return serve(fresh, referenceService::years, kodikApiClient::years);
    }

    @GetMapping("/qualities")
    @Operation(summary = "List qualities (cached)")
    public Mono<ResponseEntity<KodikReferenceResponse<KodikQualityDto>>> qualities(
            @RequestParam(defaultValue = "false") boolean fresh) {
        return serve(fresh, referenceService::qualities, kodikApiClient::qualities);
    }

    private <T> Mono<ResponseEntity<KodikReferenceResponse<T>>> serve(
            boolean fresh,
            Supplier<KodikReferenceResponse<T>> cached,
            Supplier<Mono<KodikReferenceResponse<T>>> live) {
        if (fresh) {
            return live.get().map(ResponseEntity::ok);
        }
        return Mono.fromCallable(cached::get)
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }
}
