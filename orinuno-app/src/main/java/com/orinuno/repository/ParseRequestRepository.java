package com.orinuno.repository;

import com.orinuno.model.OrinunoParseRequest;
import com.orinuno.model.ParseRequestPhase;
import com.orinuno.model.ParseRequestStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ParseRequestRepository {

    void insert(OrinunoParseRequest request);

    Optional<OrinunoParseRequest> findById(@Param("id") Long id);

    Optional<OrinunoParseRequest> findActiveByHash(@Param("hash") String hash);

    /**
     * Selects id of next PENDING request with FOR UPDATE SKIP LOCKED. Must be called within a
     * transaction; subsequent {@link #markClaimed} completes the claim atomically.
     */
    Optional<Long> selectPendingIdForClaim();

    void markClaimed(
            @Param("id") Long id,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("heartbeat") LocalDateTime heartbeat);

    void updatePhase(
            @Param("id") Long id,
            @Param("phase") ParseRequestPhase phase,
            @Param("heartbeat") LocalDateTime heartbeat);

    void updateProgress(
            @Param("id") Long id,
            @Param("decoded") int decoded,
            @Param("total") int total,
            @Param("heartbeat") LocalDateTime heartbeat);

    void markDone(
            @Param("id") Long id,
            @Param("resultContentIds") String resultContentIds,
            @Param("finishedAt") LocalDateTime finishedAt);

    void markFailed(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("retryCount") int retryCount);

    int recoverStale(
            @Param("staleBeforeTs") LocalDateTime staleBeforeTs,
            @Param("maxRetries") int maxRetries);

    List<OrinunoParseRequest> findAll(
            @Param("status") ParseRequestStatus status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countByStatus(@Param("status") ParseRequestStatus status);
}
