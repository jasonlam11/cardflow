"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";

import { ErrorBox, Loading } from "@/components/States";
import { TransactionTable } from "@/components/TransactionTable";
import { api } from "@/lib/api";
import { humanize, timeAgo } from "@/lib/format";

function Stat({ label, value, hint }: { label: string; value: string | number; hint?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="text-xs uppercase text-slate-500">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{value}</div>
      {hint && <div className="mt-1 text-xs text-slate-500">{hint}</div>}
    </div>
  );
}

export default function Overview() {
  const stats = useQuery({ queryKey: ["stats"], queryFn: api.stats, refetchInterval: 3_000 });
  const recent = useQuery({ queryKey: ["transactions", "recent"], queryFn: () => api.transactions(), refetchInterval: 3_000 });

  const s = stats.data;
  const total = s ? Object.values(s.last24hByStatus).reduce((a, b) => a + b, 0) : 0;
  const fallback = s?.last24hScoredBy.RULES_FALLBACK ?? 0;
  const scored = s ? (s.last24hScoredBy.MODEL ?? 0) + fallback : 0;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Overview</h1>
      {stats.isError && <ErrorBox error={stats.error} />}
      {s && fallback > 0 && (
        <div role="status" className="rounded-md border border-violet-300 bg-violet-50 p-3 text-sm text-violet-900 dark:border-violet-800 dark:bg-violet-950 dark:text-violet-200">
          {fallback} of {scored} charges in the last 24h were scored by the rules fallback (fraud-service unavailable).
        </div>
      )}
      {!s && !stats.isError ? (
        <Loading />
      ) : s ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label="Charges (24h)" value={total} />
          <Stat label="Approved (24h)" value={s.last24hByStatus.APPROVED ?? 0} />
          <Stat
            label="Declined (24h)"
            value={s.last24hByStatus.DECLINED ?? 0}
            hint={Object.entries(s.last24hDeclineReasons).map(([k, v]) => `${humanize(k)}: ${v}`).join(" · ") || undefined}
          />
          <Link href="/reviews" className="block">
            <Stat
              label="Awaiting review"
              value={s.pendingReviews}
              hint={s.oldestPendingSince ? `oldest ${timeAgo(s.oldestPendingSince)}` : "queue is clear"}
            />
          </Link>
        </div>
      ) : null}

      <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="font-medium">Live transactions</h2>
          <span className="text-xs text-slate-500">refreshes every 3s</span>
        </div>
        {recent.isPending ? <Loading /> : recent.isError ? <ErrorBox error={recent.error} /> : <TransactionTable rows={recent.data.content.slice(0, 15)} />}
      </section>
    </div>
  );
}
