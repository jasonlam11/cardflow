"use client";

import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { BandBadge } from "@/components/Badges";
import { ReviewPanel, type ResolvedSummary } from "@/components/ReviewPanel";
import { Empty, ErrorBox, Loading } from "@/components/States";
import { api } from "@/lib/api";
import { formatMoney, formatScore, maskCard, timeAgo } from "@/lib/format";

/** useSearchParams needs a Suspense boundary so the rest of the page can be prerendered. */
export default function ReviewQueuePage() {
  return (
    <Suspense fallback={<Loading />}>
      <ReviewQueue />
    </Suspense>
  );
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function ReviewQueue() {
  // Deep link: /reviews?id=<authorization> opens that charge even if it isn't on the first page of the queue
  const linked = useSearchParams().get("id");
  const [selected, setSelected] = useState<string | null>(linked && UUID.test(linked) ? linked : null);
  const [lastResolved, setLastResolved] = useState<ResolvedSummary | null>(null);
  const queue = useQuery({ queryKey: ["reviews"], queryFn: () => api.reviews(), refetchInterval: 3_000 });

  const items = queue.data?.content ?? [];
  // Keep showing a just-resolved item until the analyst picks another
  const current = selected ?? items[0]?.id ?? null;

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold">Review queue</h1>
        {queue.data && (
          <span className="text-sm text-slate-500">
            {queue.data.totalElements} waiting · oldest first
            {queue.data.totalElements > items.length ? ` · showing the ${items.length} oldest` : ""}
          </span>
        )}
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
                  onClick={() => {
                    setSelected(a.id);
                    setLastResolved(null);
                  }}
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
          <div className="space-y-4">
            {lastResolved && (
              <p
                role="status"
                className={`rounded-md p-3 text-sm ${lastResolved.status === "APPROVED" ? "bg-emerald-50 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200" : "bg-rose-50 text-rose-900 dark:bg-rose-950 dark:text-rose-200"}`}
              >
                {lastResolved.status === "APPROVED" ? "Approved" : "Rejected"}: {lastResolved.label}. Showing the next charge in the queue.
              </p>
            )}
          <div className="rounded-lg border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
            {current ? (
              <ReviewPanel
                key={current}
                id={current}
                onResolved={(summary) => {
                  // Advance to the next (oldest) item, and confirm what just happened
                  setLastResolved(summary);
                  setSelected(null);
                }}
              />
            ) : (
              <Empty>Select a charge to review.</Empty>
            )}
          </div>
          </div>
        </div>
      )}
    </div>
  );
}
