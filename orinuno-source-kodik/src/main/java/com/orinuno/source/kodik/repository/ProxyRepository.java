/*
 * ProxyRepository — ADR 0021 §D1b-2.
 *
 * MyBatis mapper for kodik_proxy rows in the orinuno_source_kodik schema.
 * Ported verbatim from orinuno-app/.../repository/ProxyRepository.
 */
package com.orinuno.source.kodik.repository;

import com.orinuno.source.kodik.model.KodikProxy;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProxyRepository {

    Optional<KodikProxy> findById(@Param("id") Long id);

    List<KodikProxy> findAllActive();

    void insert(KodikProxy proxy);

    void updateStatus(@Param("id") Long id, @Param("status") String status);

    void incrementFailCount(@Param("id") Long id);

    void updateLastUsedAt(@Param("id") Long id);

    void deleteById(@Param("id") Long id);
}
