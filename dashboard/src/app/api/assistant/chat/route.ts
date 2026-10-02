import { z } from "zod";

import { assistantApi, errorResponse, uuidOrThrow, UpstreamError } from "@/lib/server/backend";

const bodySchema = z.object({ cardId: z.string(), message: z.string().trim().min(1).max(2000) });

export async function POST(request: Request) {
  try {
    const parsed = bodySchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) throw new UpstreamError(400, "Ask a question of up to 2,000 characters");
    const result = await assistantApi("/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ cardId: uuidOrThrow(parsed.data.cardId), message: parsed.data.message }),
    });
    return Response.json(result);
  } catch (e) {
    return errorResponse(e);
  }
}
