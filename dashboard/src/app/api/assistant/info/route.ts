import { assistantApi, errorResponse } from "@/lib/server/backend";

export async function GET() {
  try {
    return Response.json(await assistantApi("/info"));
  } catch (e) {
    return errorResponse(e);
  }
}
