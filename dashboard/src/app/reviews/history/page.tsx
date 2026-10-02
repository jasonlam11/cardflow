"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { StatusBadge } from "@/components/Badges";
import { Pagination } from "@/components/Pagination";
import { Empty, ErrorBox, Loading } from "@/components/States";
import { api } from "@/lib/api";
import { formatDateTime, formatMoney, formatScore, maskCard } from "@/lib/format";

export default function DecisionHistory() {
  const [page, setPage] = useState(0);
  const query = useQuery({ queryKey: ["decisions", page], queryFn: () => api.decisions(page), refetchInterval: 5_000 });

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Decisions</h1>
        <p className="text-sm text-slate-500">Every analyst decision, newest first. This record is append-only.</p>
      </div>
      <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        {query.isPending ? (
          <Loading />
        ) : query.isError ? (
          <ErrorBox error={query.error} />
        ) : query.data.content.length === 0 ? (
          <Empty>No decisions yet.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-slate-200 text-xs uppercase text-slate-500 dark:border-slate-800">
                <tr>
                  <th className="py-2 pr-4 font-medium">Decided</th>
                  <th className="py-2 pr-4 font-medium">Analyst</th>
                  <th className="py-2 pr-4 font-medium">Decision</th>
                  <th className="py-2 pr-4 font-medium">Charge</th>
                  <th className="py-2 pr-4 font-medium">Score</th>
                  <th className="py-2 pr-4 font-medium">Note</th>
                </tr>
              </thead>
              <tbody>
                {query.data.content.map((d) => (
                  <tr key={d.id} className="border-b border-slate-100 align-top dark:border-slate-900">
                    <td className="py-2 pr-4 whitespace-nowrap text-slate-500">{formatDateTime(d.decidedAt)}</td>
                    <td className="py-2 pr-4">{d.analyst}</td>
                    <td className="py-2 pr-4">
                      <StatusBadge status={d.newStatus} />
                    </td>
                    <td className="py-2 pr-4">
                      {formatMoney(d.amountMinor, d.currency)} at {d.merchantName}
                      <div className="font-mono text-xs text-slate-500">{maskCard(d.cardLast4)}</div>
                    </td>
                    <td className="py-2 pr-4 font-mono">{formatScore(d.fraudScore)}</td>
                    <td className="py-2 pr-4 text-slate-600 dark:text-slate-400">{d.note ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pagination page={page} totalPages={query.data.totalPages} onChange={setPage} />
          </div>
        )}
      </section>
    </div>
  );
}
