import httpx

from cardflow_sim.dataset import DatasetGenerator, write_csv
from cardflow_sim.replay import replay


class FakeClient:
    """Behaves like authorization-service: a reused idempotency key with a different body is a 409."""

    def __init__(self):
        self.seen: dict[str, dict] = {}
        self.cards = 0

    def create_card(self, credit_limit_minor: int, currency: str = "USD") -> str:
        self.cards += 1
        return f"card-{self.cards}"

    def authorize(self, idempotency_key: str, body: dict, correlation_id: str | None = None) -> httpx.Response:
        if idempotency_key in self.seen and self.seen[idempotency_key] != body:
            return httpx.Response(409, json={"title": "Idempotency key reused"})
        self.seen[idempotency_key] = body
        return httpx.Response(201, json={"status": "APPROVED"})


def test_replaying_twice_against_the_same_stack_works(tmp_path):
    # Regression: keys were derived only from the dataset, so a second `make demo` (new cards, same keys)
    # got 409 for every charge and replayed nothing.
    path = str(tmp_path / "d.csv.gz")
    write_csv(DatasetGenerator(seed=1, cards=3, days=4).generate(), path)
    client = FakeClient()

    first = replay(client, path, from_day=2, warmup_days=1, max_cards=None, rate=0, results_path=None)
    second = replay(client, path, from_day=2, warmup_days=1, max_cards=None, rate=0, results_path=None)

    assert first["APPROVED"] > 0
    assert second["APPROVED"] == first["APPROVED"]
    assert second.get("ERROR", 0) == 0
