"""
CardFlow simulator.

  python -m cardflow_sim [live] --cards 20 --charges 500 --rate 20      random live traffic (v1)
  python -m cardflow_sim dataset --out data/transactions.csv.gz         labeled training data
  python -m cardflow_sim replay --file data/transactions.csv.gz         held-out days through the real API
"""

import argparse
import json
import os
import sys
import time
from collections import Counter

from .client import AuthorizationClient
from .dataset import DatasetGenerator, write_csv
from .generator import TrafficGenerator
from .replay import replay

DEFAULT_URL = os.environ.get("AUTHORIZATION_URL", "http://localhost:8082")


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0].startswith("-"):
        argv.insert(0, "live")  # v1 behaviour stays the default

    p = argparse.ArgumentParser(prog="cardflow_sim", description="CardFlow synthetic traffic")
    sub = p.add_subparsers(dest="command", required=True)

    live = sub.add_parser("live", help="random live traffic with client retries (v1)")
    live.add_argument("--url", default=DEFAULT_URL)
    live.add_argument("--cards", type=int, default=20)
    live.add_argument("--charges", type=int, default=200, help="0 = run until interrupted")
    live.add_argument("--rate", type=float, default=10.0, help="charges per second")
    live.add_argument("--retry-rate", type=float, default=0.05)
    live.add_argument("--seed", type=int, default=None)

    ds = sub.add_parser("dataset", help="write a labeled synthetic history (CSV, .gz ok)")
    ds.add_argument("--out", default="data/transactions.csv.gz")
    ds.add_argument("--cards", type=int, default=1_500)
    ds.add_argument("--days", type=int, default=90)
    ds.add_argument("--seed", type=int, default=7)

    rp = sub.add_parser("replay", help="send held-out days through the API and measure detection")
    rp.add_argument("--url", default=DEFAULT_URL)
    rp.add_argument("--file", default="data/transactions.csv.gz")
    rp.add_argument("--from-day", type=int, default=77, help="first measured day (test period)")
    rp.add_argument("--warmup-days", type=int, default=14, help="days before from-day sent to build history")
    rp.add_argument("--max-cards", type=int, default=None)
    rp.add_argument("--rate", type=float, default=0, help="requests per second, 0 = as fast as possible")
    rp.add_argument("--results", default=None, help="optional CSV of per-transaction outcomes")

    args = p.parse_args(argv)
    if args.command == "dataset":
        return _dataset(args)
    if args.command == "replay":
        return _replay(args)
    return _live(args)


def _dataset(args) -> int:
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    rows = DatasetGenerator(seed=args.seed, cards=args.cards, days=args.days).generate()
    write_csv(rows, args.out)
    fraud = sum(r.is_fraud for r in rows)
    print(f"wrote {len(rows)} rows ({fraud} fraud, {100 * fraud / len(rows):.2f}%) to {args.out}")
    return 0


def _replay(args) -> int:
    client = AuthorizationClient(args.url, timeout=10)
    try:
        started = time.monotonic()
        summary = replay(client, args.file, args.from_day, args.warmup_days, args.max_cards, args.rate, args.results)
        summary["seconds"] = round(time.monotonic() - started, 1)
    finally:
        client.close()
    print("summary:", json.dumps(summary, sort_keys=True))
    return 1 if summary.get("ERROR") else 0


def _live(args) -> int:
    gen = TrafficGenerator(seed=args.seed, retry_rate=args.retry_rate)
    client = AuthorizationClient(args.url)
    stats: Counter[str] = Counter()
    try:
        card_ids = [client.create_card(gen.credit_limit_minor()) for _ in range(args.cards)]
        print(f"created {len(card_ids)} cards; sending charges at {args.rate}/s to {args.url}", flush=True)

        interval = 1.0 / args.rate if args.rate > 0 else 0
        sent = 0
        next_at = time.monotonic()
        while args.charges == 0 or sent < args.charges:
            charge = gen.charge(card_ids)
            r = client.authorize(charge.idempotency_key, charge.body())
            stats[_outcome(r)] += 1
            sent += 1

            if r.status_code in (200, 201, 202) and gen.should_retry():
                again = client.authorize(charge.idempotency_key, charge.body())
                same = again.status_code == r.status_code and again.json().get("id") == r.json().get("id")
                replayed = again.headers.get("Idempotent-Replayed") == "true"
                stats["retries_sent"] += 1
                stats["retries_replayed_ok" if same and replayed else "retries_MISMATCH"] += 1

            if sent % 100 == 0:
                print(f"  {sent} sent: {dict(stats)}", flush=True)
            next_at += interval
            time.sleep(max(0.0, next_at - time.monotonic()))
    except KeyboardInterrupt:
        pass
    finally:
        client.close()

    print("summary:", dict(sorted(stats.items())), flush=True)
    return 1 if stats["retries_MISMATCH"] or stats["errors"] else 0


def _outcome(r) -> str:
    if r.status_code == 201:
        return "approved"
    if r.status_code == 202:
        return "pending_review"
    if r.status_code == 200:
        return "declined_" + str(r.json().get("declineReason", "UNKNOWN")).lower()
    return "errors"


if __name__ == "__main__":
    sys.exit(main())
