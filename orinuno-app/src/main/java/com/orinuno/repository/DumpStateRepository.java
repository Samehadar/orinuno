package com.orinuno.repository;

import com.orinuno.model.OrinunoDumpState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code orinuno_dump_state} (DUMP-1). Idempotent upsert pattern: callers always
 * invoke {@link #upsertCheckResult} after a poll; the row is created on first sight (one per
 * dump_name) and overwritten on every subsequent poll.
 */
@Mapper
public interface DumpStateRepository {

    /** Create-or-replace by {@code dump_name}. Used on every poll. */
    void upsertCheckResult(
            @Param("dumpName") String dumpName,
            @Param("dumpUrl") String dumpUrl,
            @Param("lastCheckedAt") LocalDateTime lastCheckedAt,
            @Param("lastChangedAt") LocalDateTime lastChangedAt,
            @Param("lastStatus") Integer lastStatus,
            @Param("lastErrorMessage") String lastErrorMessage,
            @Param("etag") String etag,
            @Param("lastModifiedHeader") String lastModifiedHeader,
            @Param("contentLength") Long contentLength,
            @Param("consecutiveFailures") Integer consecutiveFailures);

    Optional<OrinunoDumpState> findByName(@Param("dumpName") String dumpName);

    List<OrinunoDumpState> findAll();
}
