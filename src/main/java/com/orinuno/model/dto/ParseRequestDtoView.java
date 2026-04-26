package com.orinuno.model.dto;

import com.orinuno.model.ParseRequestPhase;
import com.orinuno.model.ParseRequestStatus;
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
