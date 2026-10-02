import type { Authorization } from "@/lib/schemas";

export function authorization(overrides: Partial<Authorization> = {}): Authorization {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    status: "PENDING_REVIEW",
    declineReason: null,
    cardId: "22222222-2222-4222-8222-222222222222",
    cardLast4: "4242",
    merchantId: "m-giftcards-01",
    merchantName: "CardMart Gift Cards",
    mcc: "5999",
    amountMinor: 30000,
    currency: "USD",
    channel: "ECOMMERCE",
    merchantCountry: null,
    fraudScore: 0.91,
    fraudBand: "REVIEW",
    fraudReasons: [
      { code: "HIGH_VELOCITY", description: "Many transactions on this card in the last few minutes", contribution: 2.0 },
      { code: "HIGH_RISK_MERCHANT_CATEGORY", description: "Merchant category frequently targeted by fraud", contribution: 1.0 },
    ],
    scoredBy: "MODEL",
    modelVersion: "fraud-xgb-test",
    occurredAt: "2026-03-01T12:00:00Z",
    createdAt: "2026-03-01T12:00:01Z",
    ...overrides,
  };
}
