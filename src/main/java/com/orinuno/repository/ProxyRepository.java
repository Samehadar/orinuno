package com.orinuno.repository;

import com.orinuno.model.KodikProxy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

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
