"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { api, ApiError } from "@/lib/api";
import { useAnalyst } from "@/lib/analyst";
import { formatDateTime, formatMoney, formatScore, humanize, maskCard, mccLabel } from "@/lib/format";

import { BandBadge, ScoredByNote, StatusBadge } from "./Badges";
import { ReasonBars } from "./ReasonBars";
import { ErrorBox, Loading } from "./States";
import { TransactionTable } from "./TransactionTable";

/** One flagged charge, its reasons and card context, and the approve/reject controls. */
export function ReviewPanel({ id, onResolved }: { id: string; onResolved?: () => void }) {
  const queryClient = useQueryClient();
  const { analyst } = useAnalyst();
  const [note, setNote] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const detail = useQuery({ queryKey: ["review", id], queryFn: () => api.review(id) });

  const decide = useMutation({
    mutationFn: (decision: "APPROVE" | "REJECT") =>
      api.decide(id, { decision, analyst: analyst.trim(), note: note.trim() || undefined }),
    onSuccess: () => {
      setNote("");
      queryClient.invalidateQueries({ queryKey: ["reviews"] });
      queryClient.invalidateQueries({ queryKey: ["stats"] });
      queryClient.invalidateQueries({ queryKey: ["review", id] });
      onResolved?.();
    },
    onError: (e) => {
      // Someone else resolved it first: refresh so the panel shows the real state
      if (e instanceof ApiError && e.status === 409) queryClient.invalidateQueries({ queryKey: ["review", id] });
    },
  });

  function submit(decision: "APPROVE" | "REJECT") {
    setFormError(null);
    if (!analyst.trim()) return setFormError("Enter your name in the Analyst box at the top first.");
    if (decision === "REJECT" && !note.trim()) return setFormError("Add a note explaining the rejection.");
    decide.mutate(decision);
  }

  if (detail.isPending) return <Loading />;
  if (detail.isError) return <ErrorBox error={detail.error} />;
  const a = detail.data.authorization;
  const pending = a.status === "PENDING_REVIEW";

  return (
    <section aria-labelledby="review-title" className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 id="review-title" className="text-2xl font-semibold">
            {formatMoney(a.amountMinor, a.currency)} at {a.merchantName}
          </h2>
          <p className="mt-1 text-sm text-slate-500">
            {mccLabel(a.mcc)} · {a.channel === "ECOMMERCE" ? "online" : a.merchantCountry ? `in person, ${a.merchantCountry}` : "in person"} ·
            card <span className="font-mono">{maskCard(a.cardLast4)}</span> · {formatDateTime(a.occurredAt ?? a.createdAt)}
          </p>
        </div>
        <StatusBadge status={a.status} />
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <div className="rounded-lg border border-slate-200 p-4 dark:border-slate-800">
          <div className="text-xs uppercase text-slate-500">Fraud score</div>
          <div className="mt-1 font-mono text-2xl">{formatScore(a.fraudScore)}</div>
        </div>
        <div className="rounded-lg border border-slate-200 p-4 dark:border-slate-800">
          <div className="text-xs uppercase text-slate-500">Band</div>
          <div className="mt-2 flex gap-2">
            <BandBadge band={a.fraudBand} />
            <ScoredByNote scoredBy={a.scoredBy} />
          </div>
        </div>
        <div className="rounded-lg border border-slate-200 p-4 dark:border-slate-800">
          <div className="text-xs uppercase text-slate-500">Model</div>
          <div className="mt-1 truncate font-mono text-sm" title={a.modelVersion ?? undefined}>
            {a.modelVersion ?? "rules"}
          </div>
        </div>
      </div>

      <div>
        <h3 className="mb-3 font-medium">Why it was flagged</h3>
        <ReasonBars reasons={a.fraudReasons} />
      </div>

      {pending ? (
        <div className="space-y-3 rounded-lg border border-slate-200 p-4 dark:border-slate-800">
          <label className="block text-sm font-medium" htmlFor="review-note">
            Note <span className="font-normal text-slate-500">(required to reject)</span>
          </label>
          <textarea
            id="review-note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            maxLength={1000}
            rows={2}
            className="w-full rounded-md border border-slate-300 bg-transparent p-2 text-sm dark:border-slate-700"
            placeholder="e.g. Cardholder confirmed the purchase by phone"
          />
          {formError && <p role="alert" className="text-sm text-rose-700 dark:text-rose-300">{formError}</p>}
          {decide.isError && <ErrorBox error={decide.error} />}
          <div className="flex gap-3">
            <button
              onClick={() => submit("APPROVE")}
              disabled={decide.isPending}
              className="rounded-md bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-800 disabled:opacity-50"
            >
              Approve
            </button>
            <button
              onClick={() => submit("REJECT")}
              disabled={decide.isPending}
              className="rounded-md bg-rose-700 px-4 py-2 text-sm font-medium text-white hover:bg-rose-800 disabled:opacity-50"
            >
              Reject
            </button>
          </div>
        </div>
      ) : (
        <p role="status" className="rounded-md bg-slate-100 p-3 text-sm dark:bg-slate-900">
          This charge has been resolved: {humanize(a.status)}
          {a.declineReason ? ` (${humanize(a.declineReason)})` : ""}.
        </p>
      )}

      <div>
        <h3 className="mb-2 font-medium">Recent activity on this card</h3>
        {detail.data.recentCardActivity.length ? (
          <TransactionTable rows={detail.data.recentCardActivity} compact />
        ) : (
          <p className="text-sm text-slate-500">No other charges on this card.</p>
        )}
      </div>
    </section>
  );
}
