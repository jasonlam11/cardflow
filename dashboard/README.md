# dashboard

Operations UI for CardFlow (Next.js 16, React 19, TypeScript, Tailwind 4, TanStack Query, Zod).

- **Overview**: 24h counts, review queue size, fallback warning, live transactions (polls every 3 s)
- **Transactions**: filter by status and risk band; cards shown as `•••• 1234` only
- **Review queue**: oldest first; amount, merchant, category, location, score, top-3 reasons (bars sized by SHAP contribution), card history; approve or reject (note required)
- **Decisions**: the append-only audit trail
- **Card**: limit, available credit, ledger balance, recent charges

## How it talks to the backends
The browser only calls this app's `/api/*` route handlers (a backend-for-frontend). They call authorization-service and ledger-service from the server, adding `X-Admin-Api-Key` from a server-only env var, and validate every filter and id before it goes into a backend URL. Responses are checked with Zod schemas in the browser.

| Env var | Default |
|---|---|
| `AUTHORIZATION_URL` | `http://localhost:8082` |
| `LEDGER_URL` | `http://localhost:8081` |
| `ADMIN_API_KEY` | none (admin calls fail closed) |

## Develop
Node 24 (`nvm use`).
```bash
npm install
ADMIN_API_KEY=... npm run dev     # http://localhost:3000, against `make up`
npm test                          # Vitest unit tests
npm run lint && npm run typecheck
npm run test:e2e                  # Playwright against the running compose stack
```
