package com.cardflow.authorization.authorization;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AuthorizationRepository extends JpaRepository<Authorization, UUID> {

    Optional<Authorization> findByIdempotencyKey(String idempotencyKey);

    long countByIdempotencyKey(String idempotencyKey);

    /** Row lock so two analysts deciding the same charge are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Authorization a WHERE a.id = :id")
    Optional<Authorization> findByIdForUpdate(@Param("id") UUID id);
}
