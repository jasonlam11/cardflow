import { authorizationApi, authorizationPublicApi, errorResponse, ledgerApi, uuidOrThrow, UpstreamError } from "@/lib/server/backend";

/** Combines the card (authorization-service), its ledger balance (ledger-service) and recent charges. */
export async function GET(_request: Request, ctx: RouteContext<"/api/cards/[id]">) {
  try {
    const id = uuidOrThrow((await ctx.params).id);
    const [card, recent] = await Promise.all([
      authorizationPublicApi(`/cards/${id}`),
      authorizationApi(`/authorizations?cardId=${id}&page=0&size=20`),
    ]);

    // The ledger account only exists once the card has an approved charge
    let ledger = null;
    try {
      const account = await ledgerApi(`/accounts?externalRef=${encodeURIComponent(`card:${id}`)}`);
      const balance = await ledgerApi(`/accounts/${account.id}/balance`);
      ledger = {
        accountId: account.id,
        balanceMinor: balance.balanceMinor,
        totalDebitsMinor: balance.totalDebitsMinor,
        totalCreditsMinor: balance.totalCreditsMinor,
      };
    } catch (e) {
      if (!(e instanceof UpstreamError && e.status === 404)) throw e;
    }

    return Response.json({
      card: {
        id: card.id,
        last4: card.last4,
        status: card.status,
        creditLimitMinor: card.creditLimitMinor,
        availableCreditMinor: card.availableCreditMinor,
        currency: card.currency,
        createdAt: card.createdAt,
      },
      ledger,
      recent: recent.content,
    });
  } catch (e) {
    return errorResponse(e);
  }
}
