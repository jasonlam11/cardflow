import type { Reason } from "@/lib/schemas";

/**
 * The model's top reasons, with bar length proportional to how much each one
 * pushed the score up (its SHAP contribution). Fallback-rule reasons have no
 * contribution and are listed without bars.
 */
export function ReasonBars({ reasons }: { reasons: Reason[] }) {
  if (reasons.length === 0) {
    return <p className="text-sm text-slate-500">No risk reasons recorded.</p>;
  }
  const max = Math.max(...reasons.map((r) => r.contribution ?? 0), 0.0001);
  return (
    <ul className="space-y-3" aria-label="Risk reasons">
      {reasons.map((r) => {
        const pct = r.contribution != null ? Math.round((r.contribution / max) * 100) : null;
        return (
          <li key={r.code}>
            <div className="flex items-baseline justify-between gap-4 text-sm">
              <span className="font-medium">{r.description}</span>
              <span className="font-mono text-xs text-slate-500">{r.code}</span>
            </div>
            {pct != null && (
              <div
                className="mt-1 h-2 rounded bg-slate-200 dark:bg-slate-700"
                role="meter"
                aria-label={`${r.code} contribution`}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={pct}
              >
                <div className="h-2 rounded bg-rose-500" style={{ width: `${pct}%` }} />
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
