# 1. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
CardFlow involves many design tradeoffs (outbox, double-entry, circuit breakers, and more). Six months from now, in an interview or a code review, the *why* behind each one matters as much as the code.

## Decision
We record every significant decision as a short Architecture Decision Record (ADR) in `docs/adr/`, numbered in order, using this format: Context, Decision, Consequences. ADRs are never edited after they're accepted. If a decision changes, a new ADR supersedes the old one.

## Consequences
- Decisions and their tradeoffs can be reviewed alongside the code.
- Writing one takes a little time for each major decision.
