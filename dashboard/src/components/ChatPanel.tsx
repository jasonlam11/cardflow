"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import { api } from "@/lib/api";
import type { ChatResponse } from "@/lib/schemas";

import { ErrorBox } from "./States";

type Exchange = { question: string; response?: ChatResponse; error?: string };

const EXAMPLES = [
  "Is a 7-hour flight delay covered?",
  "How much did I spend last month?",
  "What are 10,000 points worth?",
  "What is my credit score?",
];

const GUARDRAIL_NOTES: Record<string, string> = {
  uncited: "The model's answer had no citations, so it was withheld.",
  invalid_citation: "The model cited a source it wasn't given, so its answer was withheld.",
  ungrounded_amount: "The model stated an amount not found in the sources, so its answer was withheld.",
  max_rounds: "The question needed too many steps.",
  model_refusal: "The model declined this request.",
  empty: "The model returned an empty answer.",
};

/** Chat with the assistant about one card. Every answer shows where it came from. */
export function ChatPanel({ cardId }: { cardId: string }) {
  const [history, setHistory] = useState<Exchange[]>([]);
  const [message, setMessage] = useState("");
  const info = useQuery({ queryKey: ["assistant-info"], queryFn: api.assistantInfo, staleTime: 60_000 });

  const ask = useMutation({
    mutationFn: (question: string) => api.chat(cardId, question),
    onMutate: (question) => setHistory((h) => [...h, { question }]),
    onSuccess: (response) => setHistory((h) => [...h.slice(0, -1), { ...h[h.length - 1], response }]),
    onError: (e) => setHistory((h) => [...h.slice(0, -1), { ...h[h.length - 1], error: (e as Error).message }]),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    const q = message.trim();
    if (!q || ask.isPending) return;
    setMessage("");
    ask.mutate(q);
  }

  return (
    <section aria-label="Assistant chat" className="space-y-4">
      {info.data?.demoMode && (
        <p role="status" className="rounded-md border border-sky-300 bg-sky-50 p-3 text-sm text-sky-900 dark:border-sky-800 dark:bg-sky-950 dark:text-sky-200">
          <strong>Demo mode:</strong> no LLM API key is configured, so answers come from a simple rule-based stand-in.
          Retrieval, tools, citations and guardrails are all real.
        </p>
      )}
      {info.data && !info.data.demoMode && <p className="text-xs text-slate-500">Model: {info.data.model}</p>}

      <ol className="space-y-4" aria-live="polite">
        {history.map((x, i) => (
          <li key={i} className="space-y-2">
            <p className="ml-auto w-fit max-w-[80%] rounded-lg bg-slate-900 px-3 py-2 text-sm text-white dark:bg-slate-100 dark:text-slate-900">
              {x.question}
            </p>
            {x.error ? (
              <ErrorBox error={new Error(x.error)} />
            ) : !x.response ? (
              <p className="text-sm text-slate-500" role="status">
                Thinking…
              </p>
            ) : (
              <Answer response={x.response} />
            )}
          </li>
        ))}
      </ol>

      {history.length === 0 && (
        <div className="flex flex-wrap gap-2">
          {EXAMPLES.map((q) => (
            <button
              key={q}
              type="button"
              onClick={() => ask.mutate(q)}
              className="rounded-full border border-slate-300 px-3 py-1 text-sm hover:bg-slate-100 dark:border-slate-700 dark:hover:bg-slate-900"
            >
              {q}
            </button>
          ))}
        </div>
      )}

      <form onSubmit={submit} className="flex gap-2">
        <label htmlFor="chat-input" className="sr-only">
          Ask about this card
        </label>
        <input
          id="chat-input"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          maxLength={2000}
          placeholder="Ask about benefits or your spending…"
          className="flex-1 rounded-md border border-slate-300 bg-transparent px-3 py-2 text-sm dark:border-slate-700"
        />
        <button
          type="submit"
          disabled={ask.isPending || !message.trim()}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-40 dark:bg-slate-100 dark:text-slate-900"
        >
          Ask
        </button>
      </form>
    </section>
  );
}

function Answer({ response: r }: { response: ChatResponse }) {
  // Citation markers like [doc:x#y] are shown as chips below instead of raw text
  const text = r.answer.replace(/\s*\[(doc|tool):[a-z0-9_#-]+\]/g, "");
  return (
    <div
      className={`max-w-[80%] rounded-lg border p-3 text-sm ${r.refused ? "border-amber-300 bg-amber-50 dark:border-amber-800 dark:bg-amber-950" : "border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900"}`}
    >
      <p>{text}</p>
      {r.citations.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-1" aria-label="Sources">
          {r.citations.map((c) => (
            <li key={`${c.type}:${c.id}`} className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300">
              {c.type === "doc" ? "📄" : "🔧"} {c.title}
            </li>
          ))}
        </ul>
      )}
      {r.guardrail && GUARDRAIL_NOTES[r.guardrail] && <p className="mt-2 text-xs text-amber-800 dark:text-amber-300">Guardrail: {GUARDRAIL_NOTES[r.guardrail]}</p>}
      <p className="mt-2 text-xs text-slate-500">
        {r.toolsUsed.length ? `Looked up: ${r.toolsUsed.join(", ").replace(/_/g, " ")} · ` : ""}
        {r.latencyMs} ms{r.usage.costUsd > 0 ? ` · $${r.usage.costUsd.toFixed(4)}` : ""}
      </p>
    </div>
  );
}
