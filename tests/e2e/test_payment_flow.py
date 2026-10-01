import random

from conftest import auth_sql, charge, compose, create_card, ledger_sql, wait_until


def ledger_postings_for(auth_ids: list[str]) -> dict[str, int]:
    """How many ledger transactions reference each authorization id (should be exactly 1)."""
    ids = ",".join(f"'{i}'" for i in auth_ids)
    rows = ledger_sql(f"""
        SELECT substring(description from 'authorization ([0-9a-f-]{{36}})') AS auth_id, COUNT(*)
          FROM transactions
         WHERE substring(description from 'authorization ([0-9a-f-]{{36}})') IN ({ids})
         GROUP BY 1""")
    return {line.split("|")[0]: int(line.split("|")[1]) for line in rows.splitlines() if line}


def card_balance(card_id: str) -> int:
    value = ledger_sql(f"""
        SELECT COALESCE(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
          FROM accounts a JOIN ledger_entries e ON e.account_id = a.id
         WHERE a.external_ref = 'card:{card_id}'""")
    return int(value or 0)


def test_charges_flow_to_ledger_exactly_once(auth):
    card = create_card(auth, limit=200_000)
    rng = random.Random(42)
    approved: dict[str, int] = {}
    retries = 0

    for _ in range(40):
        amount = rng.randint(100, 9_000)
        key = f"e2e-{rng.getrandbits(64):x}"
        r = charge(auth, card, amount, key)
        assert r.status_code in (200, 201), r.text
        if r.status_code == 201:
            approved[r.json()["id"]] = amount
        # Every 4th request is retried with the same key, like a client that timed out
        if rng.random() < 0.25:
            again = charge(auth, card, amount, key)
            assert again.headers["Idempotent-Replayed"] == "true"
            assert again.json()["id"] == r.json()["id"]
            retries += 1

    assert approved, "expected some approvals"
    assert retries > 0

    ids = list(approved)
    wait_until(lambda: len(ledger_postings_for(ids)) == len(ids), message="ledger to post every approval")

    postings = ledger_postings_for(ids)
    assert all(count == 1 for count in postings.values()), f"duplicates: {postings}"
    assert card_balance(card) == sum(approved.values())


def test_kafka_outage_loses_no_transactions(auth):
    card = create_card(auth, limit=500_000)
    compose("stop", "kafka")
    try:
        ids = []
        for i in range(10):
            r = charge(auth, card, 1_000 + i)
            assert r.status_code == 201, "authorization must keep working while Kafka is down"
            ids.append(r.json()["id"])

        unsent = int(auth_sql(
            "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND payload->'payload'->>'authorizationId' IN ("
            + ",".join(f"'{i}'" for i in ids) + ")"))
        assert unsent == 10, "events should be waiting in the outbox"
    finally:
        compose("start", "kafka")

    wait_until(lambda: len(ledger_postings_for(ids)) == 10, timeout=120,
               message="ledger to catch up after Kafka returns")
    assert all(c == 1 for c in ledger_postings_for(ids).values())
    assert card_balance(card) == sum(1_000 + i for i in range(10))
