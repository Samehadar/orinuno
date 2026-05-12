/*
 * ParseRequestDtoView — ADR 0021 §D-prep.
 *
 * Wire format for GET /api/v1/parse/requests/{id} responses in source-kodik.
 * Ported field-for-field from orinuno-app's record.
 */
package com.orinuno.source.kodik.model.dto;

import com.orinuno.source.kodik.model.ParseRequestPhase;
import com.orinuno.source.kodik.model.ParseRequestStatus;
import java.time.LocalDateTime;
import java.util.List;

public record ParseRequestDtoView(
        Long id,
        String requestHash,
        ParseRequestStatus status,
        ParseRequestPhase phase,
        Integer progressDecoded,
        Integer progressTotal,
        List<Long> resultContentIds,
        String errorMessage,
        Integer retryCount,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime lastHeartbeatAt) {}
