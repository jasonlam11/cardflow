"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { BandBadge } from "@/components/Badges";
import { ReviewPanel } from "@/components/ReviewPanel";
import { Empty, ErrorBox, Loading } from "@/components/States";
import { api } from "@/lib/api";
import { formatMoney, formatScore, maskCard, timeAgo } from "@/lib/format";

export default function ReviewQueue() {
  const [selected, setSelected] = useState<string | null>(null);
  const queue = useQuery({ queryKey: ["reviews"], queryFn: () => api.reviews(), refetchInterval: 3_000 });

  const items = queue.data?.content ?? [];
  // Keep showing a just-resolved item until the analyst picks another
  const current = selected ?? items[0]?.id ?? null;

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold">Review queue</h1>
        {queue.data && <span className="text-sm text-slate-500">{queue.data.totalElements} waiting · oldest first</span>}
      </div>
      {queue.isPending ? (
        <Loading />
      ) : queue.isError ? (
        <ErrorBox error={queue.error} />
      ) : (
        <div className="grid gap-6 lg:grid-cols-[22rem_1fr]">
          <ul className="space-y-2" aria-label="Charges awaiting review">
            {items.length === 0 && <Empty>Nothing to review. 🎉</Empty>}
            {items.map((a) => (
              <li key={a.id}>
                <button
                  onClick={() => setSelected(a.id)}
                  aria-current={current === a.id ? "true" : undefined}
                  className={`w-full rounded-lg border p-3 text-left ${current === a.id ? "border-slate-900 bg-white dark:border-slate-100 dark:bg-slate-900" : "border-slate-200 bg-white hover:border-slate-400 dark:border-slate-800 dark:bg-slate-900"}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium">{formatMoney(a.amountMinor, a.currency)}</span>
                    <BandBadge band={a.fraudBand} />
                  </div>
                  <div className="mt-1 text-sm text-slate-600 dark:text-slate-400">{a.merchantName}</div>
                  <div className="mt-1 flex justify-between text-xs text-slate-500">
                    <span className="font-mono">{maskCard(a.cardLast4)}</span>
                    <span>score {formatScore(a.fraudScore)} · {timeAgo(a.createdAt)}</span>
                  </div>
                </button>
              </li>
            ))}
          </ul>
          <div className="rounded-lg border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
            {current ? (
              <ReviewPanel key={current} id={current} onResolved={() => setSelected(null)} />
            ) : (
              <Empty>Select a charge to review.</Empty>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
