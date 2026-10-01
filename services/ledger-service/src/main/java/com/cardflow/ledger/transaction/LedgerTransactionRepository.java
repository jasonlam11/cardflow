package com.cardflow.ledger.transaction;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    /** Loads the transaction and its entries in one query. */
    @EntityGraph(attributePaths = "entries")
    Optional<LedgerTransaction> findWithEntriesById(UUID id);
}
