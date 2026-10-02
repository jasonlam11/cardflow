# 9. Dashboard as a backend-for-frontend, with an admin API key

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
Analysts need to list every transaction, see fraud reasons and approve or reject held charges. Those are powerful operations: they move money (an approval reaches the ledger) and expose every cardholder's activity. The plan rules out a full user login system for the demo, and in AWS only the dashboard's port should be reachable from the internet.

## Decision
- **Backend-for-frontend (BFF):** the browser only calls the dashboard's own `/api/*` route handlers. Those handlers call authorization-service and ledger-service **server-side**.
- **Admin endpoints require `X-Admin-Api-Key`** (listing, review queue and detail, decisions, stats). The key lives in a server-only env var on the dashboard (no `NEXT_PUBLIC_` prefix), so it is never in browser JavaScript. Verified by searching the built client bundles: 0 occurrences. authorization-service compares it in constant time and rejects everything if no key is configured (**fail closed**). Merchant-facing endpoints (`POST /authorizations`, `/cards`) are unaffected.
- The BFF **whitelists** every filter and validates every id (UUID pattern) before it reaches a backend URL, and returns only the backend's safe `detail` message, never raw bodies.
- The browser validates every response with **Zod** schemas.
- The analyst types their name; it's recorded on every decision.

## Alternatives considered
- **Browser calls the services directly (CORS):** every service exposed publicly, and the key would have to live in the browser.
- **Real authentication (OIDC login, JWT per analyst, roles):** the right answer in production, and the stretch goal. It would let the backend verify *who* decided, not just that the call came from the dashboard.
- **Network-level isolation only:** in AWS the backends will be private anyway, but defense in depth means the backend should still check.

## Consequences
- The key proves "this request came from the dashboard", **not** which analyst it was. The analyst name is self-declared, so the audit trail is honest only as long as analysts are. This is the main gap that real authentication would close.
- One more hop (browser → dashboard → service) adds a few milliseconds, which is irrelevant for an operations screen.
- Rotating the key means restarting both the dashboard and authorization-service with the new value (SSM Parameter Store in Phase 7).
