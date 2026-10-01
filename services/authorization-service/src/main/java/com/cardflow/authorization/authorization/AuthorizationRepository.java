package com.cardflow.authorization.authorization;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorizationRepository extends JpaRepository<Authorization, UUID> {

    Optional<Authorization> findByIdempotencyKey(String idempotencyKey);

    long countByIdempotencyKey(String idempotencyKey);
}
