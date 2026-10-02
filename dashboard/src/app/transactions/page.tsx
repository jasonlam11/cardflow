"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { Pagination } from "@/components/Pagination";
import { Empty, ErrorBox, Loading } from "@/components/States";
import { TransactionTable } from "@/components/TransactionTable";
import { api } from "@/lib/api";

const select = "rounded-md border border-slate-300 bg-transparent px-2 py-1 text-sm dark:border-slate-700";

export default function Transactions() {
  const [status, setStatus] = useState("");
  const [band, setBand] = useState("");
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ["transactions", status, band, page],
    queryFn: () => api.transactions({ status: status || undefined, band: band || undefined, page }),
    refetchInterval: 5_000,
    placeholderData: (prev) => prev,
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Transactions</h1>
      <div className="flex flex-wrap gap-4">
        <label className="flex items-center gap-2 text-sm">
          Status
          <select className={select} value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
            <option value="">All</option>
            <option value="APPROVED">Approved</option>
            <option value="DECLINED">Declined</option>
            <option value="PENDING_REVIEW">Pending review</option>
          </select>
        </label>
        <label className="flex items-center gap-2 text-sm">
          Risk band
          <select className={select} value={band} onChange={(e) => { setBand(e.target.value); setPage(0); }}>
            <option value="">All</option>
            <option value="LOW">Low</option>
            <option value="REVIEW">Review</option>
            <option value="HIGH">High</option>
          </select>
        </label>
        {query.data && <span className="self-center text-sm text-slate-500">{query.data.totalElements.toLocaleString()} matching</span>}
      </div>
      <section className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        {query.isPending ? (
          <Loading />
        ) : query.isError ? (
          <ErrorBox error={query.error} />
        ) : query.data.content.length === 0 ? (
          <Empty>No transactions match these filters.</Empty>
        ) : (
          <>
            <TransactionTable rows={query.data.content} />
            <Pagination page={page} totalPages={query.data.totalPages} onChange={setPage} />
          </>
        )}
      </section>
    </div>
  );
}
