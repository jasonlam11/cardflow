# 10. Human review as a single guarded state transition with an append-only audit trail

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
Borderline fraud scores put a charge in `PENDING_REVIEW` (Phase 3): credit is held and nothing reaches the ledger. A human must approve or reject it. Two analysts might act on the same charge at once, decisions must be explainable later (disputes, audits, retraining the model), and a bug or a manual SQL session must not be able to quietly change a past decision.

## Decision
- **One allowed transition:** `PENDING_REVIEW` → `APPROVED` or `DECLINED` (`ANALYST_REJECTED`). Nothing else about an authorization can change, and rows can't be deleted. Enforced twice: in Java (`Authorization.resolveReview`) and by a **Postgres trigger** that rejects any other update or delete.
- **Concurrency:** the decision locks the authorization row (`SELECT … FOR UPDATE`) and re-checks it is still pending. The second analyst waits, then gets **409 Conflict**; the UI shows "already resolved" and refreshes. Tested with two simultaneous decisions: exactly one succeeds. A `UNIQUE` constraint on `review_decisions.authorization_id` is the backstop.
- **Same transaction for the effects:** the status change, the `review_decisions` row (analyst, note, before/after status, time) and, on approval, the `transaction.authorized` outbox event commit together, so an approval can't be recorded without reaching the ledger, or vice versa.
- **`review_decisions` is append-only** (trigger), like the ledger.
- Rejections require a note; the queue is oldest-first so nothing waits forever.

## Consequences
- Every borderline decision has a who, what, when and why that can't be edited afterwards.
- Analyst decisions are labelled data: approved = legit, rejected = fraud. A future retraining pipeline can learn from them (not built yet).
- An approved charge reaches the ledger at the time of approval, not the time of purchase; the event's `occurredAt` reflects the approval. Settlement-date semantics would need a separate field.
- Reversing a mistaken decision isn't possible by design; it would be a new, separate correction flow (not built).
