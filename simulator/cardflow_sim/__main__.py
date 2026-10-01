"""Run: python -m cardflow_sim --cards 20 --charges 500 --rate 20"""

import argparse
import os
import sys
import time
from collections import Counter

from .client import AuthorizationClient
from .generator import TrafficGenerator


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="CardFlow synthetic traffic simulator")
    p.add_argument("--url", default=os.environ.get("AUTHORIZATION_URL", "http://localhost:8082"))
    p.add_argument("--cards", type=int, default=20)
    p.add_argument("--charges", type=int, default=200, help="0 = run until interrupted")
    p.add_argument("--rate", type=float, default=10.0, help="charges per second")
    p.add_argument("--retry-rate", type=float, default=0.05, help="fraction of requests resent with the same key")
    p.add_argument("--seed", type=int, default=None)
    args = p.parse_args(argv)

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

            if r.status_code in (200, 201) and gen.should_retry():
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
    if r.status_code == 200:
        return "declined_" + str(r.json().get("declineReason", "UNKNOWN")).lower()
    return "errors"


if __name__ == "__main__":
    sys.exit(main())
