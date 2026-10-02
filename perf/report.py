"""
After a load test: how long events took to move through the pipeline, and whether
anything was posted twice. Reads both databases (as the admin) via docker exec.

  python3 perf/report.py --since "2026-10-02 06:00"
"""

import argparse
import json
import subprocess


def sql(db: str, query: str) -> list[list[str]]:
    out = subprocess.run(["docker", "exec", "cardflow-postgres", "psql", "-U", "cardflow_admin", "-d", db, "-tA",
                          "-F", "\t", "-c", query], check=True, capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.strip().splitlines() if line]


def pct(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", required=True, help="UTC timestamp the load test started")
    since = ap.parse_args().since

    status = dict(sql("authorization", f"""SELECT status, COUNT(*) FROM authorizations
                                            WHERE created_at >= '{since}' GROUP BY 1"""))
    publish = [float(r[0]) for r in sql("authorization", f"""
        SELECT EXTRACT(EPOCH FROM published_at - created_at) * 1000 FROM outbox_events
         WHERE created_at >= '{since}' AND published_at IS NOT NULL""")]
    unpublished = sql("authorization", f"SELECT COUNT(*) FROM outbox_events WHERE created_at >= '{since}' AND published_at IS NULL")[0][0]
    created = {r[0]: r[1] for r in sql("authorization", f"""
        SELECT id::text, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') FROM outbox_events
         WHERE created_at >= '{since}'""")}
    processed = {r[0]: r[1] for r in sql("ledger", f"""
        SELECT event_id::text, to_char(processed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') FROM processed_events
         WHERE processed_at >= '{since}'""")}
    from datetime import datetime
    end_to_end = [(datetime.fromisoformat(processed[k]) - datetime.fromisoformat(v)).total_seconds() * 1000
                  for k, v in created.items() if k in processed]
    duplicates = sql("ledger", f"""SELECT COUNT(*) FROM (
        SELECT substring(description from 'authorization ([0-9a-f-]{{36}})') FROM transactions
         WHERE created_at >= '{since}' AND description LIKE '%(authorization %' GROUP BY 1 HAVING COUNT(*) > 1) d""")[0][0]

    report = {
        "authorizations_by_status": {k: int(v) for k, v in status.items()},
        "events_created": len(created), "events_unpublished": int(unpublished),
        "events_posted_to_ledger": len([k for k in created if k in processed]),
        "outbox_publish_ms": {"p50": round(pct(publish, 50)), "p95": round(pct(publish, 95)), "max": round(max(publish or [0]))},
        "approval_to_ledger_ms": {"p50": round(pct(end_to_end, 50)), "p95": round(pct(end_to_end, 95)),
                                  "max": round(max(end_to_end or [0]))},
        "duplicate_ledger_postings": int(duplicates),
    }
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
