package com.cardflow.authorization.review;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {
}
