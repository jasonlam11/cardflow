from fastapi.testclient import TestClient

from assistant.api import create_app
from assistant.chat import Assistant
from assistant.llm.base import LLMUnavailable
from assistant.llm.fake import DemoLLM, ScriptedLLM
from evals.fixture_ledger import FIXTURE_CARD, FixtureLedger


def client(retriever, llm=None):
    return TestClient(create_app(Assistant(llm or DemoLLM(), retriever, FixtureLedger())))


def test_chat_returns_grounded_answer(retriever):
    with client(retriever) as c:
        r = c.post("/chat", json={"cardId": FIXTURE_CARD, "message": "What's my balance?"})
    body = r.json()
    assert r.status_code == 200
    assert "$989.89" in body["answer"]
    assert body["citations"] == [{"type": "tool", "id": "get_balance", "title": "get balance"}]
    assert body["demoMode"] is True and body["refused"] is False


def test_validation(retriever):
    with client(retriever) as c:
        assert c.post("/chat", json={"cardId": "not-a-card", "message": "hi"}).status_code == 422
        assert c.post("/chat", json={"cardId": FIXTURE_CARD, "message": "x" * 2001}).status_code == 422
        assert c.post("/chat", json={"cardId": FIXTURE_CARD, "message": "   "}).status_code == 422


def test_llm_outage_is_a_clean_503(retriever):
    class Down:
        name, model = "down", "down"

        def respond(self, *a):
            raise LLMUnavailable("boom")

    with client(retriever, Down()) as c:
        r = c.post("/chat", json={"cardId": FIXTURE_CARD, "message": "Am I covered for trip delays?"})
    assert r.status_code == 503
    assert r.json()["title"] == "Assistant unavailable"
    assert "boom" not in r.text


def test_info_and_correlation(retriever):
    with client(retriever, ScriptedLLM()) as c:
        r = c.get("/info", headers={"X-Correlation-Id": "abc-123"})
    assert r.headers["X-Correlation-Id"] == "abc-123"
    assert r.json()["provider"] == "scripted"


def test_metrics_endpoint(retriever):
    with client(retriever) as c:
        c.post("/chat", json={"cardId": FIXTURE_CARD, "message": "What's my balance?"})
        text = c.get("/metrics").text
    assert 'assistant_chats_total{outcome="answered"}' in text
    assert "assistant_chat_seconds_bucket" in text
    assert "assistant_llm_cost_usd_total" in text
