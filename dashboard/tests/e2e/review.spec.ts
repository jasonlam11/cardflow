import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import path from "node:path";

import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * The human-review flow end to end, through the browser:
 * flagged charge → review queue → analyst decision → ledger / credit effects → audit trail.
 *
 * To create review items deterministically, fraud-service is stopped while they
 * are created, so the rule-based fallback routes $2,000+ charges to review.
 */
const AUTH = process.env.AUTHORIZATION_URL ?? "http://localhost:8082";
const LEDGER = process.env.LEDGER_URL ?? "http://localhost:8081";
const REPO = path.resolve(__dirname, "../../..");
const RUN = randomUUID().slice(0, 8);
const ANALYST = `E2E Analyst ${RUN}`;

let cardId = "";
let approveId = "";
let rejectId = "";

function compose(...args: string[]) {
  execFileSync("docker", ["compose", ...args], { cwd: REPO, stdio: "ignore" });
}

async function pendingCharge(request: APIRequestContext, amountMinor: number, merchantName: string) {
  const res = await request.post(`${AUTH}/authorizations`, {
    headers: { "Idempotency-Key": randomUUID() },
    data: { cardId, merchantId: `m-e2e-${RUN}`, merchantName, mcc: "5411", amountMinor, currency: "USD" },
  });
  expect(res.status(), await res.text()).toBe(202);
  return (await res.json()).id as string;
}

test.describe.serial("human review", () => {
  test.beforeAll(async ({ request }) => {
    const card = await request.post(`${AUTH}/cards`, { data: { creditLimitMinor: 10_000_000, currency: "USD" } });
    cardId = (await card.json()).id;
    compose("stop", "fraud-service");
    try {
      approveId = await pendingCharge(request, 250_000, `E2E Approve ${RUN}`);
      rejectId = await pendingCharge(request, 260_000, `E2E Reject ${RUN}`);
    } finally {
      compose("start", "fraud-service");
    }
  });

  test("approving a flagged charge posts it to the ledger", async ({ page, request }) => {
    // The queue may hold older items (it's oldest first), so open ours by deep link
    await page.goto(`/reviews?id=${approveId}`);
    await page.getByLabel("Analyst").fill(ANALYST);
    await expect(page.getByRole("heading", { name: `$2,500.00 at E2E Approve ${RUN}` })).toBeVisible();
    await expect(page.getByText("Large transaction amount")).toBeVisible();

    const clicked = Date.now();
    await page.getByLabel(/Note/).fill("Cardholder confirmed by phone");
    await page.getByRole("button", { name: "Approve" }).click();
    // Confirmation, then the panel advances to the next charge in the queue
    await expect(page.getByRole("status").filter({ hasText: `Approved: $2,500.00 at E2E Approve ${RUN}` })).toBeVisible();

    // The ledger only hears about it after approval: outbox → Kafka → ledger consumer
    await expect
      .poll(async () => {
        const acct = await request.get(`${LEDGER}/accounts`, { params: { externalRef: `card:${cardId}` } });
        if (!acct.ok()) return null;
        const balance = await request.get(`${LEDGER}/accounts/${(await acct.json()).id}/balance`);
        return (await balance.json()).balanceMinor;
      }, { timeout: 30_000 })
      .toBe(250_000);
    test.info().annotations.push({ type: "metric", description: `approve click → ledger posted: ${Date.now() - clicked} ms` });
    console.log(`METRIC approve-to-ledger-ms=${Date.now() - clicked}`);
  });

  test("rejecting requires a note and releases the held credit", async ({ page, request }) => {
    await page.goto(`/reviews?id=${rejectId}`);
    await page.getByLabel("Analyst").fill(ANALYST);
    await expect(page.getByRole("heading", { name: `$2,600.00 at E2E Reject ${RUN}` })).toBeVisible();

    await page.getByRole("button", { name: "Reject" }).click();
    // (Next.js also renders an empty role="alert" route announcer, so match ours by text)
    await expect(page.getByRole("alert").filter({ hasText: "Add a note" })).toBeVisible();

    await page.getByLabel(/Note/).fill("Cardholder does not recognise it");
    await page.getByRole("button", { name: "Reject" }).click();
    await expect(page.getByRole("status").filter({ hasText: `Rejected: $2,600.00 at E2E Reject ${RUN}` })).toBeVisible();

    // Only the approved $2,500 still uses credit; the rejected hold is released
    const card = await (await request.get(`${AUTH}/cards/${cardId}`)).json();
    expect(card.availableCreditMinor).toBe(10_000_000 - 250_000);
  });

  test("both decisions are in the audit trail with the analyst's name", async ({ page }) => {
    await page.goto("/reviews/history");
    const rows = page.getByRole("row").filter({ hasText: ANALYST });
    await expect(rows).toHaveCount(2);
    await expect(rows.filter({ hasText: `E2E Approve ${RUN}` })).toContainText("Approved");
    await expect(rows.filter({ hasText: `E2E Reject ${RUN}` })).toContainText("Cardholder does not recognise it");
  });

  test("pending rows in the transactions table link to their review", async ({ page, request }) => {
    let pendingId = "";
    compose("stop", "fraud-service");
    try {
      pendingId = await pendingCharge(request, 270_000, `E2E Link ${RUN}`);
    } finally {
      compose("start", "fraud-service");
    }
    await page.goto("/transactions");
    await page.getByLabel("Status").selectOption("PENDING_REVIEW");
    await page.getByRole("row").filter({ hasText: `E2E Link ${RUN}` }).getByTitle("Open in review queue").click();
    await expect(page).toHaveURL(new RegExp(`/reviews\\?id=${pendingId}`));
    await expect(page.getByRole("heading", { name: `$2,700.00 at E2E Link ${RUN}` })).toBeVisible();
  });

  test("transactions page filters and masks cards", async ({ page }) => {
    await page.goto("/transactions");
    await page.getByLabel("Status").selectOption("DECLINED");
    await expect(page.getByText(`E2E Reject ${RUN}`)).toBeVisible();
    await expect(page.getByText(`E2E Approve ${RUN}`)).toHaveCount(0);
    await expect(page.locator("body")).not.toContainText("tok_");
  });

  test("card page shows limit, available credit and ledger balance", async ({ page, request }) => {
    // Earlier tests left holds on this card, so take the expected figure from the API
    const card = await (await request.get(`${AUTH}/cards/${cardId}`)).json();
    const available = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(card.availableCreditMinor / 100);
    await page.goto(`/cards/${cardId}`);
    await expect(page.getByText("Ledger balance")).toBeVisible();
    await expect(page.getByText("$100,000.00")).toBeVisible(); // limit
    await expect(page.getByText(available)).toBeVisible();
    await expect(page.getByText("$2,500.00").first()).toBeVisible(); // ledger balance: only the approved charge
  });
});
