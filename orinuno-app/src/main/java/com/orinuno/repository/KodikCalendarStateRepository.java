package com.orinuno.repository;

import com.orinuno.model.KodikCalendarState;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code kodik_calendar_state} (CAL-6). Idempotent upsert pattern keyed by
 * {@code shikimori_id}.
 */
@Mapper
public interface KodikCalendarStateRepository {

    Optional<KodikCalendarState> findByShikimoriId(@Param("shikimoriId") String shikimoriId);

    List<KodikCalendarState> findAll();

    /** Bulk-load states for a list of Shikimori ids. Returns one row per match (no nulls). */
    List<KodikCalendarState> findByShikimoriIds(@Param("shikimoriIds") List<String> shikimoriIds);

    /**
     * Insert-or-update by {@code shikimori_id}. Caller passes the full row; columns we don't want
     * to overwrite on conflict (here: {@code first_seen_at}) are protected via {@code COALESCE} in
     * the SQL.
     */
    void upsert(@Param("state") KodikCalendarState state);
}
