import { authorizationApi, errorResponse, pageParams } from "@/lib/server/backend";

export async function GET(request: Request) {
  try {
    return Response.json(await authorizationApi(`/reviews/decisions?${pageParams(new URL(request.url))}`));
  } catch (e) {
    return errorResponse(e);
  }
}
