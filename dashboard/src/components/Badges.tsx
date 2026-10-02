import { humanize } from "@/lib/format";

const STATUS_STYLES: Record<string, string> = {
  APPROVED: "bg-emerald-100 text-emerald-900 dark:bg-emerald-900/40 dark:text-emerald-200",
  DECLINED: "bg-rose-100 text-rose-900 dark:bg-rose-900/40 dark:text-rose-200",
  PENDING_REVIEW: "bg-amber-100 text-amber-900 dark:bg-amber-900/40 dark:text-amber-200",
};

const BAND_STYLES: Record<string, string> = {
  LOW: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
  REVIEW: "bg-amber-100 text-amber-900 dark:bg-amber-900/40 dark:text-amber-200",
  HIGH: "bg-rose-100 text-rose-900 dark:bg-rose-900/40 dark:text-rose-200",
};

const pill = "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap";

export function StatusBadge({ status }: { status: string }) {
  return <span className={`${pill} ${STATUS_STYLES[status] ?? ""}`}>{humanize(status)}</span>;
}

/** The band is always written out, never conveyed by color alone. */
export function BandBadge({ band }: { band: string | null }) {
  if (!band) return <span className="text-slate-400">—</span>;
  return <span className={`${pill} ${BAND_STYLES[band] ?? ""}`}>{band === "LOW" ? "Low risk" : band === "REVIEW" ? "Review" : "High risk"}</span>;
}

export function ScoredByNote({ scoredBy }: { scoredBy: string | null }) {
  if (scoredBy === "RULES_FALLBACK") {
    return (
      <span className={`${pill} bg-violet-100 text-violet-900 dark:bg-violet-900/40 dark:text-violet-200`} title="fraud-service was unavailable; rule-based fallback decided">
        Rules fallback
      </span>
    );
  }
  return null;
}
