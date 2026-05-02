package com.orinuno.controller;

import com.orinuno.client.dto.KodikListRequest;
import com.orinuno.model.dto.KodikListPageView;
import com.orinuno.service.KodikListProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Thin proxy over Kodik /list. Used by parser-kodik discovery to enumerate the catalogue without
 * exposing the raw Kodik response. Schema drift (HIGH-severity) surfaces as RFC 7234 Warning
 * header.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kodik")
@RequiredArgsConstructor
@Tag(name = "Kodik", description = "Thin proxy over the Kodik HTTP API")
public class KodikListController {

    private final KodikListProxyService proxyService;

    @GetMapping("/list")
    @Operation(summary = "Proxy GET /list — minimal subset of fields")
    public Mono<ResponseEntity<KodikListPageView>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String year,
            @RequestParam(required = false, name = "translation_id") String translationId,
            @RequestParam(required = false, name = "translation_type") String translationType,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(required = false, name = "anime_status") String animeStatus,
            @RequestParam(required = false, name = "drama_status") String dramaStatus,
            @RequestParam(required = false, name = "all_status") String allStatus,
            @RequestParam(required = false, name = "with_material_data") Boolean withMaterialData,
            @RequestParam(required = false, name = "next_page") String nextPage) {
        KodikListRequest request =
                KodikListRequest.builder()
                        .limit(limit)
                        .types(types)
                        .year(year)
                        .translationId(translationId)
                        .translationType(translationType)
                        .sort(sort)
                        .order(order)
                        .animeStatus(animeStatus)
                        .dramaStatus(dramaStatus)
                        .allStatus(allStatus)
                        .withMaterialData(withMaterialData)
                        .nextPageUrl(nextPage)
                        .build();
        return proxyService
                .list(request)
                .map(
                        proxy -> {
                            ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
                            if (proxy.schemaDriftObserved()) {
                                builder =
                                        builder.header(
                                                "Warning",
                                                "199 - \"kodik /list schema drift observed; check"
                                                        + " /actuator/drift\"");
                            }
                            return builder.body(proxy.page());
                        });
    }
}
