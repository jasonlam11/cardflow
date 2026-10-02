/** Display helpers. Money is always integer minor units (cents) until the moment it's shown. */

export function formatMoney(amountMinor: number, currency = "USD"): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(amountMinor / 100);
}

/** Only ever show the last 4 digits; there is no full card number anywhere in the system. */
export function maskCard(last4: string | null | undefined): string {
  return last4 && /^\d{4}$/.test(last4) ? `•••• ${last4}` : "••••";
}

export function formatScore(score: number | null | undefined): string {
  return score == null ? "—" : score.toFixed(3);
}

export function timeAgo(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return "—";
  const seconds = Math.round((now.getTime() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return `${Math.max(seconds, 0)}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });
}

export function humanize(code: string | null | undefined): string {
  if (!code) return "—";
  return code.toLowerCase().replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}

/** Merchant category codes used by the simulator, for readable labels. */
export const MCC_LABELS: Record<string, string> = {
  "5814": "Fast food & coffee",
  "5411": "Grocery",
  "5541": "Fuel",
  "5812": "Restaurant",
  "4121": "Rideshare",
  "4899": "Streaming",
  "5912": "Pharmacy",
  "5732": "Electronics",
  "4511": "Airline",
  "7011": "Hotel",
  "5944": "Jewelry",
  "5999": "Gift cards",
  "7995": "Gambling",
  "4829": "Money transfer",
};

export function mccLabel(mcc: string): string {
  return MCC_LABELS[mcc] ?? `MCC ${mcc}`;
}
