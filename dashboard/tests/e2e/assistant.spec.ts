import { randomUUID } from "node:crypto";

import { expect, test } from "@playwright/test";

/**
 * Assistant chat through the browser, against the real stack. Without an API key the
 * service runs in demo mode, which is deterministic; retrieval, tools, citations and
 * guardrails are the real ones.
 */
const AUTH = process.env.AUTHORIZATION_URL ?? "http://localhost:8082";
const LEDGER = process.env.LEDGER_URL ?? "http://localhost:8081";

let cardId = "";

test.beforeAll(async ({ request }) => {
  cardId = (await (await request.post(`${AUTH}/cards`, { data: { creditLimitMinor: 1_000_000, currency: "USD" } })).json()).id;
  const charges = [
    { amountMinor: 4_250, mcc: "5411", merchantName: "Fresh Market Grocery", occurredAt: "2026-09-10T12:00:00Z" },
    { amountMinor: 1_200, mcc: "5814", merchantName: "Blue Bottle Coffee", occurredAt: "2026-09-12T08:00:00Z" },
  ];
  for (const c of charges) {
    const res = await request.post(`${AUTH}/authorizations`, {
      headers: { "Idempotency-Key": randomUUID() },
      data: { cardId, merchantId: `m-${c.mcc}`, currency: "USD", channel: "CARD_PRESENT",
              merchantLocation: { lat: 40.71, lon: -74.0, country: "US" }, ...c },
    });
    expect(res.status(), await res.text()).toBe(201);
  }
  // Wait for outbox -> Kafka -> ledger
  await expect
    .poll(async () => {
      const acct = await request.get(`${LEDGER}/accounts`, { params: { externalRef: `card:${cardId}` } });
      if (!acct.ok()) return null;
      return (await (await request.get(`${LEDGER}/accounts/${(await acct.json()).id}/balance`)).json()).balanceMinor;
    }, { timeout: 30_000 })
    .toBe(5_450);
});

test("answers a balance question from the ledger, with a tool source", async ({ page }) => {
  await page.goto(`/assistant?card=${cardId}`);
  await page.getByLabel("Ask about this card").fill("What's my current balance?");
  await page.getByRole("button", { name: "Ask", exact: true }).click();
  await expect(page.getByText(/\$54\.50/)).toBeVisible();
  await expect(page.getByRole("list", { name: "Sources" })).toContainText("get balance");
});

test("answers a benefits question with a document source", async ({ page }) => {
  await page.goto(`/assistant?card=${cardId}`);
  await page.getByLabel("Ask about this card").fill("Is a 7-hour flight delay covered?");
  await page.getByRole("button", { name: "Ask", exact: true }).click();
  await expect(page.getByRole("list", { name: "Sources" })).toContainText("Travel Insurance");
});

test("card page links to the assistant for that card", async ({ page }) => {
  await page.goto(`/cards/${cardId}`);
  await page.getByRole("link", { name: "Ask the assistant about this card" }).click();
  await expect(page).toHaveURL(new RegExp(`/assistant\\?card=${cardId}`));
});
