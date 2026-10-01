package com.cardflow.ledger.transaction;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /** Newest first; ties broken by entry id so page boundaries are stable. */
    @Query(value = """
            SELECT new com.cardflow.ledger.transaction.AccountActivity(
                       t.id, t.description, t.occurredAt, e.direction, e.amountMinor, e.currency)
              FROM LedgerEntry e JOIN e.transaction t
             WHERE e.accountId = :accountId
             ORDER BY t.occurredAt DESC, e.id
            """,
            countQuery = "SELECT COUNT(e) FROM LedgerEntry e WHERE e.accountId = :accountId")
    Page<AccountActivity> findActivity(@Param("accountId") UUID accountId, Pageable pageable);

    @Query("""
            SELECT new com.cardflow.ledger.transaction.DirectionTotals(
                       COALESCE(SUM(CASE WHEN e.direction = com.cardflow.ledger.transaction.Direction.DEBIT
                                         THEN e.amountMinor ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.direction = com.cardflow.ledger.transaction.Direction.CREDIT
                                         THEN e.amountMinor ELSE 0 END), 0))
              FROM LedgerEntry e
             WHERE e.accountId = :accountId
            """)
    DirectionTotals totalsFor(@Param("accountId") UUID accountId);
}
