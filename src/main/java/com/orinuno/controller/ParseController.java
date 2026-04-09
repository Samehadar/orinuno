package com.orinuno.controller;

import com.orinuno.mapper.ContentMapper;
import com.orinuno.model.dto.ContentDto;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.service.ParserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/parse")
@RequiredArgsConstructor
@Tag(name = "Parse", description = "Trigger parsing and decoding operations")
public class ParseController {

    private final ParserService parserService;

    @PostMapping("/search")
    @Operation(summary = "Search and parse content from Kodik")
    public Mono<ResponseEntity<List<ContentDto>>> search(@Valid @RequestBody ParseRequestDto request) {
        log.info("🔍 Parse request: {}", request);
        return parserService.search(request)
                .map(contents -> contents.stream().map(ContentMapper::toDto).toList())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/decode/{contentId}")
    @Operation(summary = "Decode mp4 links for specific content")
    public Mono<ResponseEntity<Void>> decode(@PathVariable Long contentId,
                                              @RequestParam(defaultValue = "false") boolean force) {
        log.info("🔓 Decode request for content_id={}, force={}", contentId, force);
        var action = force
                ? parserService.forceDecodeForContent(contentId)
                : parserService.decodeForContent(contentId);
        return action.thenReturn(ResponseEntity.ok().<Void>build());
    }
}
