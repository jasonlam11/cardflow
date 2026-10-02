import json

import pytest

from assistant.tools import TOOL_NAMES, TOOLS, ToolError, ToolExecutor
from evals.fixture_ledger import FIXTURE_CARD, INJECTION_MERCHANT, FixtureLedger


def run(name, args, card=FIXTURE_CARD):
    return json.loads(ToolExecutor(card, FixtureLedger()).run(name, args))


def test_no_tool_can_move_money():
    # The model's whole capability surface: read-only by construction
    assert TOOL_NAMES == {"get_balance", "spending_by_category", "largest_transactions", "recent_transactions"}
    assert not any(w in t["description"].lower() for t in TOOLS for w in ("transfer", "pay ", "refund", "dispute"))


def test_tools_have_no_card_id_parameter():
    # The card comes from the session, never from the model
    for t in TOOLS:
        assert "card" not in json.dumps(t["input_schema"]).lower()


def test_balance_and_spending():
    assert run("get_balance", {})["balance"] == "$989.89"
    feb = run("spending_by_category", {"start_date": "2026-02-01", "end_date": "2026-02-28"})
    assert feb["total"] == "$659.00"
    assert feb["categories"][0] == {"category": "Airlines", "total": "$420.00", "transactions": 1}


def test_other_cards_see_nothing():
    assert run("get_balance", {}, card="someone-else")["balance"] == "$0.00"


def test_injection_text_in_merchant_names_is_passed_as_data():
    out = run("recent_transactions", {"limit": 20})
    assert any(t["merchant"] == INJECTION_MERCHANT for t in out["transactions"])


@pytest.mark.parametrize("args", [
    {"start_date": "2026-03-01", "end_date": "2026-02-01"},
    {"start_date": "2020-01-01", "end_date": "2026-01-01"},
    {"start_date": "March", "end_date": "2026-03-01"},
])
def test_bad_date_ranges_are_tool_errors(args):
    with pytest.raises(ToolError):
        ToolExecutor(FIXTURE_CARD, FixtureLedger()).run("spending_by_category", args)


def test_limits_are_enforced():
    with pytest.raises(ToolError):
        ToolExecutor(FIXTURE_CARD, FixtureLedger()).run("recent_transactions", {"limit": 500})
    with pytest.raises(ToolError):
        ToolExecutor(FIXTURE_CARD, FixtureLedger()).run("wire_money", {})
