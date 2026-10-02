import { authorizationApi, errorResponse } from "@/lib/server/backend";

export async function GET() {
  try {
    return Response.json(await authorizationApi("/stats"));
  } catch (e) {
    return errorResponse(e);
  }
}
