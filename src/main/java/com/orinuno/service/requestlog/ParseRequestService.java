package com.orinuno.service.requestlog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.OrinunoParseRequest;
import com.orinuno.model.ParseRequestPhase;
import com.orinuno.model.ParseRequestStatus;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.model.dto.ParseRequestDtoView;
import com.orinuno.repository.ParseRequestRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Application-level orchestration around the parse-request log: submit / fetch / list. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseRequestService {

    private final ParseRequestRepository repository;
    private final RequestHashService hashService;
    private final ObjectMapper objectMapper;
    private final OrinunoProperties properties;
    private final Clock clock;

    /**
     * Idempotent submit: if an active (PENDING/RUNNING) request with identical canonical hash
     * exists, returns it (created=false). Otherwise inserts a new PENDING row (created=true).
     */
    public SubmitResult submit(ParseRequestDto request, String createdBy) {
        RequestHashService.Result hashed = hashService.compute(request);

        Optional<OrinunoParseRequest> active = repository.findActiveByHash(hashed.hash());
        if (active.isPresent()) {
            log.info("♻️ Returning active parse request id={} (idempotent)", active.get().getId());
            return new SubmitResult(toView(active.get()), false);
        }

        OrinunoParseRequest entity = new OrinunoParseRequest();
        entity.setRequestHash(hashed.hash());
        entity.setRequestJson(serializeOrThrow(request));
        entity.setStatus(ParseRequestStatus.PENDING);
        entity.setPhase(ParseRequestPhase.QUEUED);
        entity.setProgressDecoded(0);
        entity.setProgressTotal(0);
        entity.setRetryCount(0);
        entity.setCreatedBy(createdBy == null || createdBy.isBlank() ? "default" : createdBy);
        entity.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
        repository.insert(entity);
        log.info(
                "📨 Submitted parse request id={} (hash={}, by={})",
                entity.getId(),
                hashed.hash(),
                entity.getCreatedBy());
        return new SubmitResult(toView(entity), true);
    }

    public Optional<ParseRequestDtoView> findById(long id) {
        return repository.findById(id).map(this::toView);
    }

    public PageResult list(ParseRequestStatus status, int limit, int offset) {
        int safeLimit = clampLimit(limit);
        int safeOffset = Math.max(0, offset);
        List<OrinunoParseRequest> rows = repository.findAll(status, safeLimit, safeOffset);
        long total = repository.countByStatus(status);
        return new PageResult(rows.stream().map(this::toView).toList(), total);
    }

    private int clampLimit(int requested) {
        OrinunoProperties.RequestsProperties cfg = properties.getRequests();
        int defaultLimit = cfg.getDefaultPageLimit();
        int max = Math.max(1, cfg.getMaxPageLimit());
        if (requested <= 0) return defaultLimit;
        return Math.min(requested, max);
    }

    private ParseRequestDtoView toView(OrinunoParseRequest e) {
        return new ParseRequestDtoView(
                e.getId(),
                e.getRequestHash(),
                e.getStatus(),
                e.getPhase(),
                e.getProgressDecoded(),
                e.getProgressTotal(),
                parseIds(e.getResultContentIds()),
                e.getErrorMessage(),
                e.getRetryCount(),
                e.getCreatedAt(),
                e.getStartedAt(),
                e.getFinishedAt(),
                e.getLastHeartbeatAt());
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("⚠️ Failed to parse result_content_ids: {}", json, e);
            return List.of();
        }
    }

    private String serializeOrThrow(ParseRequestDto request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize ParseRequestDto", e);
        }
    }

    public record PageResult(List<ParseRequestDtoView> items, long total) {}

    public record SubmitResult(ParseRequestDtoView view, boolean created) {}
}
