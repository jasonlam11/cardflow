/**
 * Server-only calls from the dashboard to the backend services.
 *
 * Only route handlers import this file. The admin API key lives in a server
 * environment variable (no NEXT_PUBLIC_ prefix), so it is never bundled into
 * browser JavaScript; the browser only ever talks to this app's /api routes.
 */
import { randomUUID } from "node:crypto";

const AUTHORIZATION_URL = process.env.AUTHORIZATION_URL ?? "http://localhost:8082";
const LEDGER_URL = process.env.LEDGER_URL ?? "http://localhost:8081";
const ASSISTANT_URL = process.env.ASSISTANT_URL ?? "http://localhost:8084";
const TIMEOUT_MS = 5_000;
// An LLM answer with a few tool calls can take several seconds
const ASSISTANT_TIMEOUT_MS = 45_000;

export class UpstreamError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
  ) {
    super(detail);
  }
}

async function call(base: string, path: string, init: RequestInit & { admin?: boolean; timeoutMs?: number } = {}) {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  headers.set("X-Correlation-Id", randomUUID());
  if (init.admin) {
    const key = process.env.ADMIN_API_KEY;
    if (!key) throw new UpstreamError(500, "Dashboard is missing ADMIN_API_KEY");
    headers.set("X-Admin-Api-Key", key);
  }
  let res: Response;
  try {
    res = await fetch(base + path, { ...init, headers, cache: "no-store", signal: AbortSignal.timeout(init.timeoutMs ?? TIMEOUT_MS) });
  } catch {
    throw new UpstreamError(502, "Backend service unavailable");
  }
  if (!res.ok) {
    // Pass through the backend's safe problem "detail", never raw bodies or stack traces
    let detail = `Backend returned ${res.status}`;
    try {
      const body = await res.json();
      if (typeof body?.detail === "string") detail = body.detail;
    } catch {
      /* not JSON */
    }
    throw new UpstreamError(res.status, detail);
  }
  return res.json();
}

export const authorizationApi = (path: string, init?: RequestInit) =>
  call(AUTHORIZATION_URL, path, { ...init, admin: true });

/** Merchant-facing authorization endpoints that don't need the admin key (e.g. /cards). */
export const authorizationPublicApi = (path: string, init?: RequestInit) => call(AUTHORIZATION_URL, path, init);

export const ledgerApi = (path: string, init?: RequestInit) => call(LEDGER_URL, path, init);

export const assistantApi = (path: string, init?: RequestInit) =>
  call(ASSISTANT_URL, path, { ...init, timeoutMs: ASSISTANT_TIMEOUT_MS });

/** Turns any thrown error into a JSON response the client can display. */
export function errorResponse(e: unknown): Response {
  if (e instanceof UpstreamError) {
    return Response.json({ error: e.detail }, { status: e.status });
  }
  console.error("Unexpected dashboard error", e);
  return Response.json({ error: "Unexpected error" }, { status: 500 });
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Validate anything that ends up in a backend URL. */
export function uuidOrThrow(value: string | null | undefined): string {
  if (!value || !UUID.test(value)) throw new UpstreamError(400, "Invalid id");
  return value;
}

export function pageParams(url: URL): string {
  const page = Math.max(0, Number.parseInt(url.searchParams.get("page") ?? "0", 10) || 0);
  const size = Math.min(100, Math.max(1, Number.parseInt(url.searchParams.get("size") ?? "25", 10) || 25));
  return `page=${page}&size=${size}`;
}
