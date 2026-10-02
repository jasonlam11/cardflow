"""The orchestrator's guarantees, tested against scripted (mis)behaviour."""

from assistant.chat import Assistant, TOO_MANY_STEPS, UNVERIFIED
from assistant.llm.base import ToolCall, UserTurn
from assistant.llm.fake import DemoLLM, ScriptedLLM
from evals.fixture_ledger import FIXTURE_CARD, TODAY, FixtureLedger


def ask(llm, question, retriever):
    return Assistant(llm, retriever, FixtureLedger()).answer(FIXTURE_CARD, question, today=TODAY)


def test_grounded_doc_answer_passes(retriever):
    r = ask(ScriptedLLM("Trip delays over 6 hours are covered up to $500 per ticket [doc:travel-insurance#trip-delay]."),
            "Is a 7 hour flight delay covered?", retriever)
    assert not r.refused and r.guardrail is None
    assert r.citations == [{"type": "doc", "id": "travel-insurance#trip-delay", "title": "Travel Insurance: Trip delay"}]


def test_uncited_answer_is_replaced(retriever):
    r = ask(ScriptedLLM("Yes, you're covered."), "Is a 7 hour flight delay covered?", retriever)
    assert r.refused and r.guardrail == "uncited" and r.answer == UNVERIFIED


def test_citation_to_a_section_not_retrieved_is_replaced(retriever):
    r = ask(ScriptedLLM("Covered [doc:rewards#earning-rates]."), "Is a 7 hour flight delay covered?", retriever)
    assert r.guardrail == "invalid_citation"


def test_made_up_amount_is_replaced(retriever):
    r = ask(ScriptedLLM("Covered up to $9,999 [doc:travel-insurance#trip-delay]."), "Is a 7 hour flight delay covered?",
            retriever)
    assert r.guardrail == "ungrounded_amount"


def test_tool_result_amounts_are_allowed_and_tool_citations_checked(retriever):
    llm = ScriptedLLM([ToolCall("t1", "get_balance", {})], "Your balance is $989.89 [tool:get_balance].")
    r = ask(llm, "What's my balance?", retriever)
    assert not r.refused and r.tools_used == ["get_balance"]
    # Citing a tool that wasn't actually called this request is rejected
    r2 = ask(ScriptedLLM("Your balance is $0.00 [tool:get_balance]."), "What's my balance?", retriever)
    assert r2.guardrail == "invalid_citation"


def test_refusals_pass_through(retriever):
    r = ask(ScriptedLLM("I don't know. The documents don't cover credit scores."), "What is my credit score?", retriever)
    assert r.refused and r.guardrail is None and r.citations == []


def test_model_safety_refusal(retriever):
    r = ask(ScriptedLLM("<refusal>"), "anything", retriever)
    assert r.refused and r.guardrail == "model_refusal"


def test_tool_errors_go_back_to_the_model(retriever):
    llm = ScriptedLLM([ToolCall("t1", "spending_by_category", {"start_date": "x", "end_date": "y"})],
                      "I don't know. I couldn't read that date range.")
    r = ask(llm, "How much did I spend?", retriever)
    tool_results = llm.seen[1][-1].results
    assert tool_results[0].is_error and "YYYY-MM-DD" in tool_results[0].content
    assert r.refused


def test_round_limit(retriever):
    llm = ScriptedLLM(*[[ToolCall(f"t{i}", "get_balance", {})] for i in range(10)])
    r = ask(llm, "loop forever", retriever)
    assert r.guardrail == "max_rounds" and r.answer == TOO_MANY_STEPS and r.rounds == 5


def test_personal_data_is_redacted_before_the_llm(retriever):
    llm = ScriptedLLM("I don't know.")
    ask(llm, "My card 4242 4242 4242 4242 and email jo@example.com - am I covered?", retriever)
    sent = llm.seen[0][0]
    assert isinstance(sent, UserTurn)
    assert "4242 4242" not in sent.text and "jo@example.com" not in sent.text


def test_demo_llm_runs_the_whole_pipeline(retriever):
    r = ask(DemoLLM(), "How much did I spend last month?", retriever)
    assert not r.refused and r.tools_used == ["spending_by_category"]
    assert "$659.00" in r.answer
