// Shared helpers for CardFlow load tests (k6). Synthetic data only.
import http from "k6/http";
import { check } from "k6";

export const AUTH = __ENV.AUTHORIZATION_URL || "http://authorization-service:8082";
export const CARDS = Number(__ENV.CARDS || 500);

const MERCHANTS = [
  ["m-coffee-01", "Blue Bottle Coffee", "5814", 650],
  ["m-grocery-01", "Fresh Market Grocery", "5411", 6500],
  ["m-fuel-01", "Shell Station 214", "5541", 4500],
  ["m-restaurant-01", "Harbor Grill", "5812", 5800],
  ["m-pharmacy-01", "Corner Pharmacy", "5912", 2400],
  ["m-electronics-01", "Volt Electronics", "5732", 18000],
];

export function createCards() {
  const ids = [];
  for (let i = 0; i < CARDS; i++) {
    const r = http.post(`${AUTH}/cards`, JSON.stringify({ creditLimitMinor: 50_000_000, currency: "USD" }),
      { headers: { "Content-Type": "application/json" }, tags: { name: "setup" } });
    ids.push(r.json("id"));
  }
  return ids;
}

// Each card's charges are spaced 6 hours apart in the past, so the fraud model sees
// ordinary spending rather than a velocity burst caused by the load test itself.
const T0 = Date.now() - 365 * 24 * 3600 * 1000;

export function charge(cards, iteration, runId) {
  const card = cards[iteration % cards.length];
  const round = Math.floor(iteration / cards.length);
  const [merchantId, merchantName, mcc, median] = MERCHANTS[iteration % MERCHANTS.length];
  const body = JSON.stringify({
    cardId: card, merchantId, merchantName, mcc, currency: "USD",
    amountMinor: Math.max(100, Math.round(median * (0.5 + Math.random()))),
    channel: "CARD_PRESENT",
    merchantLocation: { lat: 40.71, lon: -74.0, country: "US" },
    occurredAt: new Date(T0 + round * 6 * 3600 * 1000).toISOString(),
  });
  const key = `k6-${runId}-${iteration}`;
  const params = { headers: { "Content-Type": "application/json", "Idempotency-Key": key }, tags: { name: "authorize" } };
  const res = http.post(`${AUTH}/authorizations`, body, params);
  check(res, { "authorized (201/200/202)": (r) => [200, 201, 202].includes(r.status) });

  // 10% of clients "time out" and retry with the same key: must replay, never double-charge
  if (Math.random() < 0.1) {
    const again = http.post(`${AUTH}/authorizations`, body, { ...params, tags: { name: "retry" } });
    check(again, {
      "retry replayed": (r) => r.headers["Idempotent-Replayed"] === "true",
      "retry same result": (r) => r.status === res.status && r.json("id") === res.json("id"),
    });
  }
}
