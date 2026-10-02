package com.cardflow.ledger.transaction;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

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
