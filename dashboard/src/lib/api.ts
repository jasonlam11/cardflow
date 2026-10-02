"use client";

/** Browser-side calls to this app's /api routes, with responses validated by Zod. */
import type { z } from "zod";
import {
  assistantInfoSchema,
  authorizationPageSchema,
  chatResponseSchema,
  cardDetailSchema,
  decisionPageSchema,
  reviewDetailSchema,
  reviewResultSchema,
  statsSchema,
} from "./schemas";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function getJson<T extends z.ZodType>(url: string, schema: T, init?: RequestInit): Promise<z.infer<T>> {
  const res = await fetch(url, init);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, typeof body?.error === "string" ? body.error : `Request failed (${res.status})`);
  return schema.parse(body);
}

export type TransactionFilters = { status?: string; band?: string; cardId?: string; page?: number };

export const api = {
  stats: () => getJson("/api/stats", statsSchema),
  transactions: (f: TransactionFilters = {}) => {
    const q = new URLSearchParams();
    if (f.status) q.set("status", f.status);
    if (f.band) q.set("band", f.band);
    if (f.cardId) q.set("cardId", f.cardId);
    q.set("page", String(f.page ?? 0));
    return getJson(`/api/transactions?${q}`, authorizationPageSchema);
  },
  reviews: (page = 0) => getJson(`/api/reviews?page=${page}&size=50`, authorizationPageSchema),
  review: (id: string) => getJson(`/api/reviews/${id}`, reviewDetailSchema),
  decide: (id: string, body: { decision: "APPROVE" | "REJECT"; analyst: string; note?: string }) =>
    getJson(`/api/reviews/${id}/decision`, reviewResultSchema, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  decisions: (page = 0) => getJson(`/api/decisions?page=${page}`, decisionPageSchema),
  card: (id: string) => getJson(`/api/cards/${id}`, cardDetailSchema),
  assistantInfo: () => getJson("/api/assistant/info", assistantInfoSchema),
  chat: (cardId: string, message: string) =>
    getJson("/api/assistant/chat", chatResponseSchema, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ cardId, message }),
    }),
};
