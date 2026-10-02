/**
 * Zod schemas for every response the browser receives from the BFF.
 * If a backend changes shape, parsing fails loudly instead of rendering garbage.
 */
import { z } from "zod";

export const statusSchema = z.enum(["APPROVED", "DECLINED", "PENDING_REVIEW"]);
export const bandSchema = z.enum(["LOW", "REVIEW", "HIGH"]);
export const scoredBySchema = z.enum(["MODEL", "RULES_FALLBACK", "NOT_SCORED"]);

export const reasonSchema = z.object({
  code: z.string(),
  description: z.string(),
  contribution: z.number().nullable().optional(),
});

export const authorizationSchema = z.object({
  id: z.uuid(),
  status: statusSchema,
  declineReason: z.string().nullable(),
  cardId: z.uuid().nullable(),
  cardLast4: z.string().nullable(),
  merchantId: z.string(),
  merchantName: z.string(),
  mcc: z.string(),
  amountMinor: z.number().int(),
  currency: z.string(),
  channel: z.string().nullable(),
  merchantCountry: z.string().nullable(),
  fraudScore: z.number().nullable(),
  fraudBand: bandSchema.nullable(),
  fraudReasons: z.array(reasonSchema),
  scoredBy: scoredBySchema.nullable(),
  modelVersion: z.string().nullable(),
  occurredAt: z.string().nullable(),
  createdAt: z.string(),
});

export const pageOf = <T extends z.ZodType>(item: T) =>
  z.object({
    content: z.array(item),
    page: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
  });

export const authorizationPageSchema = pageOf(authorizationSchema);

export const reviewDetailSchema = z.object({
  authorization: authorizationSchema,
  recentCardActivity: z.array(authorizationSchema),
});

export const decisionSchema = z.object({
  id: z.uuid(),
  authorizationId: z.uuid(),
  decision: z.enum(["APPROVE", "REJECT"]),
  analyst: z.string(),
  note: z.string().nullable(),
  previousStatus: z.string(),
  newStatus: z.string(),
  decidedAt: z.string(),
  amountMinor: z.number(),
  currency: z.string(),
  merchantName: z.string(),
  cardLast4: z.string().nullable(),
  fraudScore: z.number().nullable(),
});

export const decisionPageSchema = pageOf(decisionSchema);

export const statsSchema = z.object({
  last24hByStatus: z.record(z.string(), z.number()),
  last24hDeclineReasons: z.record(z.string(), z.number()),
  last24hScoredBy: z.record(z.string(), z.number()),
  pendingReviews: z.number(),
  oldestPendingSince: z.string().nullable(),
  reviewDecisionsLast24h: z.number(),
});

export const cardDetailSchema = z.object({
  card: z.object({
    id: z.uuid(),
    last4: z.string(),
    status: z.string(),
    creditLimitMinor: z.number(),
    availableCreditMinor: z.number(),
    currency: z.string(),
    createdAt: z.string(),
  }),
  ledger: z
    .object({ accountId: z.uuid(), balanceMinor: z.number(), totalDebitsMinor: z.number(), totalCreditsMinor: z.number() })
    .nullable(),
  recent: z.array(authorizationSchema),
});

export const reviewResultSchema = z.object({
  decisionId: z.uuid(),
  authorizationId: z.uuid(),
  decision: z.enum(["APPROVE", "REJECT"]),
  analyst: z.string(),
  newStatus: statusSchema,
  decidedAt: z.string(),
});

export const chatCitationSchema = z.object({ type: z.enum(["doc", "tool"]), id: z.string(), title: z.string() });

export const chatResponseSchema = z.object({
  answer: z.string(),
  citations: z.array(chatCitationSchema),
  toolsUsed: z.array(z.string()),
  refused: z.boolean(),
  guardrail: z.string().nullable(),
  model: z.string(),
  demoMode: z.boolean(),
  usage: z.object({ inputTokens: z.number(), outputTokens: z.number(), costUsd: z.number() }),
  latencyMs: z.number(),
});

export const assistantInfoSchema = z.object({
  provider: z.string(),
  model: z.string(),
  demoMode: z.boolean(),
  documents: z.number().optional(),
  sections: z.number().optional(),
});

export type Authorization = z.infer<typeof authorizationSchema>;
export type ChatResponse = z.infer<typeof chatResponseSchema>;
export type AuthorizationPage = z.infer<typeof authorizationPageSchema>;
export type ReviewDetail = z.infer<typeof reviewDetailSchema>;
export type Decision = z.infer<typeof decisionSchema>;
export type Stats = z.infer<typeof statsSchema>;
export type CardDetail = z.infer<typeof cardDetailSchema>;
export type Reason = z.infer<typeof reasonSchema>;
