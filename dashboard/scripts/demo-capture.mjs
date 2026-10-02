// Captures demo frames from the running dashboard (make demo first), then make-gif.py builds the GIF.
// Run from dashboard/:  node scripts/demo-capture.mjs
import { mkdirSync } from "node:fs";
import { chromium } from "@playwright/test";

const BASE = process.env.DASHBOARD_URL ?? "http://127.0.0.1:3000";
const AUTH = process.env.AUTHORIZATION_URL ?? "http://localhost:8082";
const OUT = new URL("../../docs/demo/frames/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });

const key = process.env.ADMIN_API_KEY;
if (!key) throw new Error("set ADMIN_API_KEY (from .env) to find a model-flagged charge");
const queue = await (await fetch(`${AUTH}/reviews?size=100`, { headers: { "X-Admin-Api-Key": key } })).json();
const flagged = queue.content.find((a) => a.scoredBy === "MODEL" && a.fraudReasons.length >= 2);
if (!flagged) throw new Error("no model-flagged charge waiting for review; run make demo");

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 760 } });
await page.addInitScript(() => localStorage.setItem("cardflow.analyst", "Jason"));
let n = 0;
const shot = async (ms) => { await page.waitForTimeout(ms ?? 600); await page.screenshot({ path: `${OUT}/${String(n++).padStart(2, "0")}.png` }); };

await page.goto(`${BASE}/`); await page.getByText("Live transactions").waitFor(); await shot(1200);
await page.goto(`${BASE}/reviews?id=${flagged.id}`); await page.getByText("Why it was flagged").waitFor(); await shot(900);
await page.getByLabel(/Note/).fill("Cardholder confirmed the purchase by phone"); await shot(300);
await page.getByRole("button", { name: "Approve", exact: true }).click();
await page.getByRole("status").filter({ hasText: "Approved:" }).waitFor(); await shot(400);
await page.goto(`${BASE}/cards/${flagged.cardId}`); await page.getByText("Ledger balance").waitFor(); await shot(2500);
await page.getByRole("link", { name: "Ask the assistant about this card" }).click();
await page.getByLabel("Ask about this card").fill("What's my current balance?"); await shot(300);
await page.getByRole("button", { name: "Ask", exact: true }).click();
await page.getByRole("list", { name: "Sources" }).first().waitFor(); await shot(400);
await page.getByLabel("Ask about this card").fill("Is a 7-hour flight delay covered?");
await page.getByRole("button", { name: "Ask", exact: true }).click();
await page.getByRole("list", { name: "Sources" }).nth(1).waitFor(); await shot(400);
await browser.close();
console.log(`captured ${n} frames to ${OUT}`);
