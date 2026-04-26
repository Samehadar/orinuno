package com.orinuno.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrinunoParseRequest {

    private Long id;
    private String requestHash;
    private String requestJson;
    private ParseRequestStatus status;
    private ParseRequestPhase phase;
    private Integer progressDecoded;
    private Integer progressTotal;
    private String resultContentIds;
    private String errorMessage;
    private Integer retryCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime lastHeartbeatAt;
}
