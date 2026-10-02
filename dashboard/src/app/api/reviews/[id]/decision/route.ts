import { z } from "zod";

import { authorizationApi, errorResponse, uuidOrThrow, UpstreamError } from "@/lib/server/backend";

const bodySchema = z.object({
  decision: z.enum(["APPROVE", "REJECT"]),
  analyst: z.string().trim().min(1).max(100),
  note: z.string().trim().max(1000).optional(),
});

export async function POST(request: Request, ctx: RouteContext<"/api/reviews/[id]/decision">) {
  try {
    const { id } = await ctx.params;
    const parsed = bodySchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) throw new UpstreamError(400, "Invalid decision");
    const result = await authorizationApi(`/authorizations/${uuidOrThrow(id)}/review`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(parsed.data),
    });
    return Response.json(result);
  } catch (e) {
    return errorResponse(e);
  }
}
