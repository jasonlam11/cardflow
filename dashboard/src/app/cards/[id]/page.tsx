"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams } from "next/navigation";

import { ErrorBox, Loading } from "@/components/States";
import { TransactionTable } from "@/components/TransactionTable";
import { api } from "@/lib/api";
import { formatDateTime, formatMoney, humanize, maskCard } from "@/lib/format";

export default function CardDetail() {
  const { id } = useParams<{ id: string }>();
  const query = useQuery({ queryKey: ["card", id], queryFn: () => api.card(id), refetchInterval: 5_000 });

  if (query.isPending) return <Loading />;
  if (query.isError) return <ErrorBox error={query.error} />;
  const { card, ledger, recent } = query.data;

  const cell = "rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900";
  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-mono text-2xl font-semibold">{maskCard(card.last4)}</h1>
        <p className="text-sm text-slate-500">
          {humanize(card.status)} · opened {formatDateTime(card.createdAt)} ·{" "}
          <Link href={`/assistant?card=${card.id}`} className="underline underline-offset-2">
            Ask the assistant about this card
          </Link>
        </p>
      </div>
      <div className="grid gap-4 sm:grid-cols-3">
        <div className={cell}>
          <div className="text-xs uppercase text-slate-500">Credit limit</div>
          <div className="mt-1 text-xl font-semibold">{formatMoney(card.creditLimitMinor, card.currency)}</div>
        </div>
        <div className={cell}>
          <div className="text-xs uppercase text-slate-500">Available credit</div>
          <div className="mt-1 text-xl font-semibold">{formatMoney(card.availableCreditMinor, card.currency)}</div>
          <div className="text-xs text-slate-500">after approved charges and holds for review</div>
        </div>
        <div className={cell}>
          <div className="text-xs uppercase text-slate-500">Ledger balance</div>
          <div className="mt-1 text-xl font-semibold">{ledger ? formatMoney(ledger.balanceMinor, card.currency) : "—"}</div>
          <div className="text-xs text-slate-500">{ledger ? "posted from the double-entry ledger" : "no posted charges yet"}</div>
        </div>
      </div>
      <section className={cell}>
        <h2 className="mb-2 font-medium">Recent authorizations</h2>
        <TransactionTable rows={recent} />
      </section>
    </div>
  );
}
