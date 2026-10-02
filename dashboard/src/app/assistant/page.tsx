"use client";

import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";

import { ChatPanel } from "@/components/ChatPanel";
import { Loading } from "@/components/States";
import { api } from "@/lib/api";
import { maskCard } from "@/lib/format";

export default function AssistantPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Assistant />
    </Suspense>
  );
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function Assistant() {
  const linked = useSearchParams().get("card");
  const [cardId, setCardId] = useState(linked && UUID.test(linked) ? linked : "");
  // Cards seen in recent approved transactions, for the demo "who am I" picker
  const recent = useQuery({ queryKey: ["transactions", "for-assistant"], queryFn: () => api.transactions({ status: "APPROVED" }) });
  const cards = [...new Map((recent.data?.content ?? []).filter((a) => a.cardId).map((a) => [a.cardId!, a.cardLast4])).entries()];
  const current = cardId || cards[0]?.[0] || "";

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Assistant</h1>
        <p className="text-sm text-slate-500">
          Answers questions about card benefits and the selected card&apos;s own spending, with sources. It can only read, never move money.
        </p>
      </div>
      <label className="flex items-center gap-2 text-sm">
        Chatting as card
        <select
          value={current}
          onChange={(e) => setCardId(e.target.value)}
          className="rounded-md border border-slate-300 bg-transparent px-2 py-1 font-mono dark:border-slate-700"
        >
          {linked && UUID.test(linked) && !cards.some(([id]) => id === linked) && <option value={linked}>{maskCard(null)} (linked)</option>}
          {cards.map(([id, last4]) => (
            <option key={id} value={id}>
              {maskCard(last4)}
            </option>
          ))}
        </select>
      </label>
      {current ? (
        <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <ChatPanel key={current} cardId={current} />
        </div>
      ) : recent.isPending ? (
        <Loading />
      ) : (
        <p className="text-sm text-slate-500">No cards with approved charges yet. Run the simulator first.</p>
      )}
    </div>
  );
}
