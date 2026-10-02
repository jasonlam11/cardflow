import { authorizationApi, errorResponse, pageParams, uuidOrThrow, UpstreamError } from "@/lib/server/backend";

const STATUSES = new Set(["APPROVED", "DECLINED", "PENDING_REVIEW"]);
const BANDS = new Set(["LOW", "REVIEW", "HIGH"]);

export async function GET(request: Request) {
  try {
    const url = new URL(request.url);
    const q = new URLSearchParams(pageParams(url));
    // Whitelist every filter before it goes into a backend URL
    const status = url.searchParams.get("status");
    const band = url.searchParams.get("band");
    const cardId = url.searchParams.get("cardId");
    if (status) {
      if (!STATUSES.has(status)) throw new UpstreamError(400, "Invalid status filter");
      q.set("status", status);
    }
    if (band) {
      if (!BANDS.has(band)) throw new UpstreamError(400, "Invalid band filter");
      q.set("band", band);
    }
    if (cardId) q.set("cardId", uuidOrThrow(cardId));
    return Response.json(await authorizationApi(`/authorizations?${q}`));
  } catch (e) {
    return errorResponse(e);
  }
}
