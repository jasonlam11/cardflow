import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ChatPanel } from "@/components/ChatPanel";

const CARD = "11111111-2222-4333-8444-555555555555";
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const reply = (over: object = {}) => ({
  answer: "Trip delays over 6 hours are covered up to $500 per ticket [doc:travel-insurance#trip-delay].",
  citations: [{ type: "doc", id: "travel-insurance#trip-delay", title: "Travel Insurance: Trip delay" }],
  toolsUsed: [],
  refused: false,
  guardrail: null,
  model: "claude-haiku-4-5",
  demoMode: false,
  usage: { inputTokens: 1200, outputTokens: 80, costUsd: 0.0016 },
  latencyMs: 900,
  ...over,
});

function setup(chat: Response, info = { provider: "anthropic", model: "claude-haiku-4-5", demoMode: false }) {
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => (url.endsWith("/info") ? json(info) : chat));
  vi.stubGlobal("fetch", fetchMock);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ChatPanel cardId={CARD} />
    </QueryClientProvider>,
  );
  return fetchMock;
}

describe("ChatPanel", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows the answer without raw citation markers, plus source chips", async () => {
    const fetchMock = setup(json(reply()));
    fireEvent.change(screen.getByLabelText("Ask about this card"), { target: { value: "Is a 7-hour delay covered?" } });
    fireEvent.click(screen.getByRole("button", { name: "Ask" }));
    expect(await screen.findByText("Trip delays over 6 hours are covered up to $500 per ticket.")).toBeTruthy();
    expect(screen.getByRole("list", { name: "Sources" }).textContent).toContain("Travel Insurance: Trip delay");
    const [, init] = fetchMock.mock.calls.find(([u]) => String(u).endsWith("/chat"))!;
    expect(JSON.parse(init!.body as string)).toEqual({ cardId: CARD, message: "Is a 7-hour delay covered?" });
  });

  it("explains when a guardrail withheld the model's answer", async () => {
    setup(json(reply({ answer: "I don't know. I couldn't verify an answer.", citations: [], refused: true, guardrail: "ungrounded_amount" })));
    fireEvent.click(screen.getByRole("button", { name: "What is my credit score?" }));
    expect(await screen.findByText(/stated an amount not found in the sources/)).toBeTruthy();
  });

  it("labels demo mode clearly", async () => {
    setup(json(reply()), { provider: "demo", model: "demo-rules", demoMode: true });
    expect(await screen.findByText(/Demo mode:/)).toBeTruthy();
  });

  it("shows a clean error when the assistant is unavailable", async () => {
    setup(json({ error: "The assistant is temporarily unavailable. Please try again shortly." }, 503));
    fireEvent.click(screen.getByRole("button", { name: "What are 10,000 points worth?" }));
    expect(await screen.findByText(/temporarily unavailable/)).toBeTruthy();
  });
});
