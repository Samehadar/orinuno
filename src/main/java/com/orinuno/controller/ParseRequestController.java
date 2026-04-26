package com.orinuno.controller;

import com.orinuno.model.ParseRequestStatus;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.model.dto.ParseRequestDtoView;
import com.orinuno.service.requestlog.ParseRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/parse/requests")
@RequiredArgsConstructor
@Tag(
        name = "ParseRequests",
        description =
                "Async parse-request log: submit a request and poll completion. parser-kodik must"
                        + " not poll status here — use GET /api/v1/export/ready?updatedSince=")
public class ParseRequestController {

    private final ParseRequestService parseRequestService;

    @PostMapping
    @Operation(
            summary = "Submit a parse request (idempotent by canonical-JSON SHA-256)",
            description =
                    "Returns 201 for newly-created requests and 200 for an existing active"
                            + " (PENDING/RUNNING) request with the same hash.")
    public ResponseEntity<ParseRequestDtoView> submit(
            @Valid @RequestBody ParseRequestDto request,
            @RequestHeader(value = "X-Created-By", required = false) String createdBy) {
        ParseRequestService.SubmitResult result = parseRequestService.submit(request, createdBy);
        HttpStatus code = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(code).body(result.view());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a single parse request by id")
    public ResponseEntity<ParseRequestDtoView> findById(@PathVariable long id) {
        return parseRequestService
                .findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(
            summary = "List parse requests",
            description =
                    "X-Total-Count header reports the total matching rows for the supplied"
                            + " status filter. Use limit=0 to obtain only the count without rows.")
    public ResponseEntity<List<ParseRequestDtoView>> list(
            @RequestParam(required = false) @Parameter(description = "Filter by status")
                    ParseRequestStatus status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit == 0) {
            ParseRequestService.PageResult head = parseRequestService.list(status, 1, 0);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_RANGE, "items 0-0/" + head.total())
                    .header("X-Total-Count", String.valueOf(head.total()))
                    .body(List.of());
        }
        ParseRequestService.PageResult result = parseRequestService.list(status, limit, offset);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.total()))
                .body(result.items());
    }
}
