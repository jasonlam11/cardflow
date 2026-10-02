"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { api } from "@/lib/api";
import { useAnalyst } from "@/lib/analyst";

const LINKS = [
  { href: "/", label: "Overview" },
  { href: "/transactions", label: "Transactions" },
  { href: "/reviews", label: "Review queue" },
  { href: "/reviews/history", label: "Decisions" },
];

export function Nav() {
  const pathname = usePathname();
  const { analyst, setAnalyst } = useAnalyst();
  const stats = useQuery({ queryKey: ["stats"], queryFn: api.stats, refetchInterval: 3_000 });
  const pending = stats.data?.pendingReviews ?? 0;

  return (
    <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-950">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
        <Link href="/" className="text-lg font-semibold tracking-tight">
          CardFlow <span className="font-normal text-slate-500">ops</span>
        </Link>
        <nav aria-label="Main" className="flex flex-wrap gap-1">
          {LINKS.map((l) => {
            const active = l.href === "/" ? pathname === "/" : pathname === l.href;
            return (
              <Link
                key={l.href}
                href={l.href}
                aria-current={active ? "page" : undefined}
                className={`rounded-md px-3 py-1.5 text-sm ${active ? "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900" : "text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-900"}`}
              >
                {l.label}
                {l.href === "/reviews" && pending > 0 && (
                  <span className="ml-2 rounded-full bg-amber-500 px-1.5 text-xs font-semibold text-white" aria-label={`${pending} pending`}>
                    {pending}
                  </span>
                )}
              </Link>
            );
          })}
        </nav>
        <label className="ml-auto flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
          Analyst
          <input
            value={analyst}
            onChange={(e) => setAnalyst(e.target.value)}
            placeholder="Your name"
            maxLength={100}
            className="w-40 rounded-md border border-slate-300 bg-transparent px-2 py-1 dark:border-slate-700"
          />
        </label>
      </div>
    </header>
  );
}
