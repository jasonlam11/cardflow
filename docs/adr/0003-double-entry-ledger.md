# 3. Double-entry ledger with derived balances

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
The ledger is the system of record for money. It must never lose, invent or silently change an amount, and every balance must be explainable from history.

## Decision
- Every transaction is a set of **at least two entries** whose **debits equal credits**, all in one currency.
- Amounts are stored as **BIGINT minor units** (cents), never floating point.
- Balances are **never stored**. They are calculated from the entries on read, using each account type's normal side (ASSET/EXPENSE increase with debits; LIABILITY/REVENUE with credits).
- Posted transactions and entries are **append-only**. Corrections are new, offsetting transactions.
- The rules are enforced in **two layers**: Java validation (clear error messages) and **Postgres** itself (deferred constraint triggers for balance, triggers blocking UPDATE/DELETE/TRUNCATE, a composite foreign key matching entry currency to account currency).

## Consequences
- An unbalanced or edited ledger can't be committed, even by a buggy service or a manual SQL session.
- Every balance can be audited back to its entries, and the API returns the debit and credit totals with it.
- Balance reads do a `SUM` over the account's entries. With an index on `account_id` this is fast for our data volumes. At very large scale you'd add periodic **balance snapshots**, so each read only sums entries made after the latest snapshot.
- Tests can't clean up by deleting rows (the database forbids it), so they use fresh UUIDs instead.
