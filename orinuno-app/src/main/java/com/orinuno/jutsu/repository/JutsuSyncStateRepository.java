package com.orinuno.jutsu.repository;

import com.orinuno.jutsu.model.JutsuSyncState;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code jutsu_sync_state} (ARCH-0016 P1a). Singleton row, always {@code id =
 * 1}; the singleton is enforced by a CHECK constraint at the DB level so the mapper API doesn't
 * need to defend against accidental {@code id != 1} rows.
 */
@Mapper
public interface JutsuSyncStateRepository {

    Optional<JutsuSyncState> findSingleton();

    /**
     * Insert the singleton row if it doesn't exist yet. No-op when the row already exists (the SQL
     * uses {@code INSERT IGNORE} on the singleton primary key). Callers can run this on every
     * worker startup and it remains cheap.
     */
    void initIfAbsent(@Param("state") JutsuSyncState state);

    /** Full overwrite of the singleton row's mutable fields. {@code id} stays {@code 1}. */
    void update(@Param("state") JutsuSyncState state);
}
