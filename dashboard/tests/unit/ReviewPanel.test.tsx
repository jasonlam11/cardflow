import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ReviewPanel } from "@/components/ReviewPanel";
import { AnalystProvider } from "@/lib/analyst";

import { authorization } from "./fixtures";

const ID = "11111111-1111-4111-8111-111111111111";
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function setup(decisionResponse: Response) {
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url.endsWith("/decision")) return decisionResponse;
    return json({ authorization: authorization(), recentCardActivity: [] });
  });
  vi.stubGlobal("fetch", fetchMock);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AnalystProvider>
        <ReviewPanel id={ID} />
      </AnalystProvider>
    </QueryClientProvider>,
  );
  return fetchMock;
}

describe("ReviewPanel", () => {
  beforeEach(() => localStorage.setItem("cardflow.analyst", "Ana Analyst"));
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it("shows the charge and its reasons", async () => {
    setup(json({}));
    expect(await screen.findByText("$300.00 at CardMart Gift Cards")).toBeTruthy();
    expect(screen.getByText("Many transactions on this card in the last few minutes")).toBeTruthy();
  });

  it("requires a note to reject", async () => {
    const fetchMock = setup(json({}));
    fireEvent.click(await screen.findByRole("button", { name: "Reject" }));
    expect(screen.getByRole("alert").textContent).toContain("Add a note");
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/decision"))).toBe(false);
  });

  it("requires an analyst name", async () => {
    localStorage.clear();
    setup(json({}));
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    expect(screen.getByRole("alert").textContent).toContain("Enter your name");
  });

  it("sends the decision with the analyst and note", async () => {
    const fetchMock = setup(
      json({ decisionId: ID, authorizationId: ID, decision: "APPROVE", analyst: "Ana Analyst", newStatus: "APPROVED", decidedAt: "2026-03-01T12:05:00Z" }),
    );
    fireEvent.change(await screen.findByLabelText(/Note/), { target: { value: "Confirmed by phone" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/decision"))).toBe(true));
    const [, init] = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/decision"))!;
    expect(JSON.parse(init!.body as string)).toEqual({ decision: "APPROVE", analyst: "Ana Analyst", note: "Confirmed by phone" });
  });

  it("explains when another analyst got there first", async () => {
    setup(json({ error: "Authorization was already resolved: APPROVED" }, 409));
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    expect(await screen.findByText(/already resolved/)).toBeTruthy();
  });
});
