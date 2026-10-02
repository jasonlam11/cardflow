# 8. One feature implementation for training and serving

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
A fraud model is trained offline on historical data and used online one transaction at a time. If the two paths compute features differently, even slightly (a different time window, an off-by-one in "last hour", including the current transaction in its own history), the model sees inputs in production it never saw in training. This **training/serving skew** silently degrades a model that looked great offline.

## Decision
- A single pure function, `compute_features(history, current)` in `fraud/features.py`, is the only feature implementation.
- **Training** replays the labeled dataset in time order, building each card's history as it goes, and calls it.
- **Serving** loads the card's history from Postgres and calls the same function.
- The history window (30 days) and cap (100 transactions) are applied **inside** the function, so both paths truncate identically even if the database returns more.
- The model's metadata stores the feature list; the service refuses to load a model whose features don't match the code.

## Alternatives considered
- **Separate SQL features for serving:** fast, but a second implementation that will drift.
- **A feature store** (e.g. Feast): the industry answer at scale, with online/offline consistency built in. Too much infrastructure for one model.
- **Precomputed rolling aggregates** updated per transaction: faster reads, but harder to replay exactly for training.

## Consequences
- No skew by construction, and feature logic has one set of unit tests.
- Serving reads up to 100 rows per score. Measured: well under 10 ms end to end locally (see PROGRESS.md); at much higher volume, precomputed aggregates or a feature store would be the next step.
- **Time-based split** (ADR-level rule in the model card): train on the past, test on the future, never random, so evaluation matches how the model is used.
