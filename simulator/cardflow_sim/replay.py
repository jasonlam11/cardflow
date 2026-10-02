"""
Replays part of a generated dataset through the real authorization API, using
each row's original timestamp as occurredAt, and measures detection end to end.

Rows before --from-day are a warm-up that builds per-card history in
fraud-service; they're sent but not counted in the metrics.
"""

import csv
import time
from collections import Counter
from datetime import datetime, timedelta

from .client import AuthorizationClient
from .dataset import EPOCH, read_csv

FLAGGED = {"PENDING_REVIEW", "FRAUD_SUSPECTED"}
CARD_LIMIT_MINOR = 10_000_000  # $100k: high so credit limits don't mask fraud decisions


def outcome(response) -> str:
    if response.status_code not in (200, 201, 202):
        return "ERROR"
    body = response.json()
    if body.get("status") == "PENDING_REVIEW":
        return "PENDING_REVIEW"
    if body.get("declineReason") == "FRAUD_SUSPECTED":
        return "FRAUD_SUSPECTED"
    return body.get("status", "UNKNOWN")


def replay(client: AuthorizationClient, path: str, from_day: int, warmup_days: int, max_cards: int | None,
           rate: float, results_path: str | None) -> dict:
    start = EPOCH + timedelta(days=from_day - warmup_days)
    measure_from = EPOCH + timedelta(days=from_day)
    rows = [r for r in read_csv(path) if datetime.fromisoformat(r["ts"]) >= start]
    if max_cards:
        keep = sorted({r["card_id"] for r in rows})[:max_cards]
        keep_set = set(keep)
        rows = [r for r in rows if r["card_id"] in keep_set]

    card_ids: dict[str, str] = {}
    stats: Counter[str] = Counter()
    results = []
    interval = 1.0 / rate if rate > 0 else 0
    next_at = time.monotonic()
    for i, r in enumerate(rows):
        if r["card_id"] not in card_ids:
            card_ids[r["card_id"]] = client.create_card(CARD_LIMIT_MINOR)
        body = {
            "cardId": card_ids[r["card_id"]], "merchantId": r["merchant_id"], "merchantName": r["merchant_name"],
            "mcc": r["mcc"], "amountMinor": int(r["amount_minor"]), "currency": r["currency"],
            "channel": r["channel"], "occurredAt": r["ts"],
        }
        if r["lat"]:
            body["merchantLocation"] = {"lat": float(r["lat"]), "lon": float(r["lon"]), "country": r["country"]}
        resp = client.authorize(f"replay-{r['card_id'][:8]}-{i}", body)
        result = outcome(resp)

        if datetime.fromisoformat(r["ts"]) >= measure_from:
            fraud = r["is_fraud"] == "1"
            flagged = result in FLAGGED
            stats["tp" if fraud and flagged else "fn" if fraud else "fp" if flagged else "tn"] += 1
            stats[result] += 1
            if results_path:
                results.append({**r, "outcome": result, "score": resp.json().get("fraudScore", "")
                                if resp.status_code < 300 else ""})
        else:
            stats["warmup_sent"] += 1

        if (i + 1) % 2_000 == 0:
            print(f"  {i + 1}/{len(rows)} sent", flush=True)
        next_at += interval
        time.sleep(max(0.0, next_at - time.monotonic()))

    if results_path and results:
        with open(results_path, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(results[0]))
            w.writeheader()
            w.writerows(results)

    tp, fp, fn = stats["tp"], stats["fp"], stats["fn"]
    summary = dict(stats)
    summary["precision"] = round(tp / (tp + fp), 4) if tp + fp else None
    summary["recall"] = round(tp / (tp + fn), 4) if tp + fn else None
    return summary
