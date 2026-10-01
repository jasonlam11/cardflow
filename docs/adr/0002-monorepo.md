# 2. Use a single monorepo for all services

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
CardFlow has six deployable parts across Java, Python and TypeScript. They could live in separate repositories (polyrepo) or in one repository (monorepo).

## Decision
Use one repository, with each service in its own folder with its own build (`services/<name>/`). CI jobs are filtered by path so a change to one service only builds and tests that service.

## Consequences
- **Pros:** one `docker compose up` for the whole system; changes that span services (for example, a new event field used by both producer and consumer) land in a single PR; one place for docs, ADRs and CI.
- **Cons:** CI needs path filters to stay fast; nothing prevents one service from importing another's code by accident, so service boundaries have to be enforced by convention and review. Each service still has its own build file and never shares source with another.
- Splitting into separate repositories later is straightforward, because services share no code.
