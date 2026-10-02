import Link from "next/link";

import { formatMoney, formatScore, humanize, maskCard, mccLabel, timeAgo } from "@/lib/format";
import type { Authorization } from "@/lib/schemas";

import { BandBadge, ScoredByNote, StatusBadge } from "./Badges";

export function TransactionTable({ rows, compact = false }: { rows: Authorization[]; compact?: boolean }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-slate-200 text-xs uppercase text-slate-500 dark:border-slate-800">
          <tr>
            <th className="py-2 pr-4 font-medium">When</th>
            <th className="py-2 pr-4 font-medium">Card</th>
            <th className="py-2 pr-4 font-medium">Merchant</th>
            <th className="py-2 pr-4 text-right font-medium">Amount</th>
            <th className="py-2 pr-4 font-medium">Status</th>
            <th className="py-2 pr-4 font-medium">Risk</th>
            {!compact && <th className="py-2 pr-4 font-medium">Top reason</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((a) => (
            <tr key={a.id} className="border-b border-slate-100 dark:border-slate-900">
              <td className="py-2 pr-4 whitespace-nowrap text-slate-500" title={a.createdAt}>
                {timeAgo(a.createdAt)}
              </td>
              <td className="py-2 pr-4 font-mono whitespace-nowrap">
                {a.cardId ? (
                  <Link className="underline-offset-2 hover:underline" href={`/cards/${a.cardId}`}>
                    {maskCard(a.cardLast4)}
                  </Link>
                ) : (
                  maskCard(null)
                )}
              </td>
              <td className="py-2 pr-4">
                <div>{a.merchantName}</div>
                <div className="text-xs text-slate-500">
                  {mccLabel(a.mcc)}
                  {a.channel === "ECOMMERCE" ? " · online" : a.merchantCountry ? ` · ${a.merchantCountry}` : ""}
                </div>
              </td>
              <td className="py-2 pr-4 text-right font-mono whitespace-nowrap">{formatMoney(a.amountMinor, a.currency)}</td>
              <td className="py-2 pr-4">
                <div className="flex flex-col items-start gap-1">
                  <StatusBadge status={a.status} />
                  {a.declineReason && <span className="text-xs text-slate-500">{humanize(a.declineReason)}</span>}
                </div>
              </td>
              <td className="py-2 pr-4">
                <div className="flex items-center gap-2">
                  <BandBadge band={a.fraudBand} />
                  <span className="font-mono text-xs text-slate-500">{formatScore(a.fraudScore)}</span>
                  <ScoredByNote scoredBy={a.scoredBy} />
                </div>
              </td>
              {!compact && (
                <td className="py-2 pr-4 text-xs text-slate-600 dark:text-slate-400" title={a.fraudReasons.map((r) => r.description).join("\n")}>
                  {a.fraudReasons[0]?.description ?? "—"}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
