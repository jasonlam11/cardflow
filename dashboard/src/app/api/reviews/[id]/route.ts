import { authorizationApi, errorResponse, uuidOrThrow } from "@/lib/server/backend";

export async function GET(_request: Request, ctx: RouteContext<"/api/reviews/[id]">) {
  try {
    const { id } = await ctx.params;
    return Response.json(await authorizationApi(`/reviews/${uuidOrThrow(id)}`));
  } catch (e) {
    return errorResponse(e);
  }
}
