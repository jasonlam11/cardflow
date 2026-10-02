package com.cardflow.authorization.card;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CardAccountRepository extends JpaRepository<CardAccount, UUID> {

    /**
     * SELECT ... FOR UPDATE: other transactions trying to lock the same card
     * wait until this one commits, so two charges can't both spend the same credit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CardAccount c WHERE c.id = :id")
    Optional<CardAccount> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT COALESCE(SUM(amount_minor), 0) FROM authorizations
             WHERE card_account_id = :cardId AND status IN ('APPROVED', 'PENDING_REVIEW')
            """, nativeQuery = true)
    /** Approved charges plus charges held for review; both reduce available credit. */
    long sumApprovedMinor(@Param("cardId") UUID cardId);
}
