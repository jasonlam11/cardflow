import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { GET as transactions } from "@/app/api/transactions/route";
import { pageParams, uuidOrThrow } from "@/lib/server/backend";

describe("BFF input handling", () => {
  beforeEach(() => vi.stubEnv("ADMIN_API_KEY", "secret-key"));
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("clamps paging", () => {
    expect(pageParams(new URL("http://x/?page=-3&size=5000"))).toBe("page=0&size=100");
    expect(pageParams(new URL("http://x/?page=abc"))).toBe("page=0&size=25");
  });

  it("only accepts UUIDs in backend paths", () => {
    expect(uuidOrThrow("11111111-1111-4111-8111-111111111111")).toBeTruthy();
    expect(() => uuidOrThrow("../../stats")).toThrow();
  });

  it("rejects filters outside the whitelist without calling the backend", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const res = await transactions(new Request("http://dash/api/transactions?status=DROP%20TABLE"));
    expect(res.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("adds the admin key server-side and passes backend errors through safely", async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ detail: "Backend says no", trace: "java.lang.Secret" }), { status: 503 }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const res = await transactions(new Request("http://dash/api/transactions?status=APPROVED"));
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(new Headers(init.headers).get("X-Admin-Api-Key")).toBe("secret-key");
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: "Backend says no" });
  });

  it("fails closed when the dashboard has no admin key", async () => {
    vi.stubEnv("ADMIN_API_KEY", "");
    vi.stubGlobal("fetch", vi.fn());
    const res = await transactions(new Request("http://dash/api/transactions"));
    expect(res.status).toBe(500);
  });
});
