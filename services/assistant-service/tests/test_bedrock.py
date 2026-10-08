"""BedrockLLM against a fake Bedrock client: no AWS account, no network, no cost."""

from types import SimpleNamespace

import anthropic
import httpx2
import pytest

from assistant.api import make_llm
from assistant.llm.anthropic_client import AnthropicLLM
from assistant.llm.base import LLMUnavailable, ToolResult, ToolResultsTurn, UserTurn
from assistant.llm.bedrock_client import BedrockLLM
from assistant.llm.pricing import canonical_model, cost_usd
from assistant.settings import Settings

MODEL = "global.anthropic.claude-haiku-4-5-20251001-v1:0"


def block(**kw):
    return SimpleNamespace(**kw)


def response(content, stop_reason="end_turn", input_tokens=1000, output_tokens=200):
    return SimpleNamespace(
        content=content, stop_reason=stop_reason, model=MODEL,
        usage=SimpleNamespace(input_tokens=input_tokens, output_tokens=output_tokens,
                              cache_read_input_tokens=None, cache_creation_input_tokens=None))


class FakeBedrock:
    def __init__(self, result):
        self.result = result
        self.requests = []
        self.messages = self  # client.messages.create(...)

    def create(self, **request):
        self.requests.append(request)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


def test_sends_a_messages_request_without_automatic_caching():
    fake = FakeBedrock(response([block(type="text", text="Hi [doc:rewards#earning]")]))
    llm = BedrockLLM(model=MODEL, region="us-east-1", client=fake)

    out = llm.respond("system prompt", [UserTurn("hello")], tools=[{"name": "get_balance"}])

    req = fake.requests[0]
    assert req["model"] == MODEL and req["system"] == "system prompt"
    assert req["messages"] == [{"role": "user", "content": "hello"}]
    assert req["tools"] == [{"name": "get_balance"}]
    assert "cache_control" not in req  # Bedrock rejects top-level automatic caching
    assert out.text == "Hi [doc:rewards#earning]" and out.stop_reason == "end_turn"


def test_tool_calls_and_tool_results_round_trip():
    fake = FakeBedrock(response(
        [block(type="tool_use", id="tu_1", name="get_balance", input={})], stop_reason="tool_use"))
    llm = BedrockLLM(model=MODEL, region="us-east-1", client=fake)

    out = llm.respond("s", [UserTurn("balance?")], tools=[])
    assert [(c.id, c.name, c.input) for c in out.tool_calls] == [("tu_1", "get_balance", {})]

    llm.respond("s", [UserTurn("balance?"), ToolResultsTurn([ToolResult("tu_1", '{"balance": 1}')])], tools=[])
    assert fake.requests[1]["messages"][1]["content"][0] == {
        "type": "tool_result", "tool_use_id": "tu_1", "content": '{"balance": 1}', "is_error": False}


def test_cost_is_estimated_at_the_base_model_price():
    llm = BedrockLLM(model=MODEL, region="us-east-1", client=FakeBedrock(response([block(type="text", text="x")])))
    out = llm.respond("s", [UserTurn("q")], tools=[])
    assert out.usage.cost_usd == pytest.approx(cost_usd("claude-haiku-4-5", 1000, 200)) == pytest.approx(0.002)


def test_refusal_returns_no_content():
    llm = BedrockLLM(model=MODEL, region="us-east-1",
                     client=FakeBedrock(response([block(type="text", text="partial")], stop_reason="refusal")))
    out = llm.respond("s", [UserTurn("q")], tools=[])
    assert out.stop_reason == "refusal" and out.text == ""


def test_aws_side_failures_mean_unavailable():
    NoCredentialsError = type("NoCredentialsError", (Exception,), {"__module__": "botocore.exceptions"})
    llm = BedrockLLM(model=MODEL, region="us-east-1", client=FakeBedrock(NoCredentialsError("no creds")))
    with pytest.raises(LLMUnavailable, match="Bedrock unavailable"):
        llm.respond("s", [UserTurn("q")], tools=[])


def test_sdk_connection_errors_mean_unavailable():
    err = anthropic.APIConnectionError(request=httpx2.Request("POST", "https://bedrock-runtime.us-east-1.amazonaws.com"))
    llm = BedrockLLM(model=MODEL, region="us-east-1", client=FakeBedrock(err))
    with pytest.raises(LLMUnavailable):
        llm.respond("s", [UserTurn("q")], tools=[])


def test_our_own_bugs_still_surface():
    llm = BedrockLLM(model=MODEL, region="us-east-1", client=FakeBedrock(KeyError("oops")))
    with pytest.raises(KeyError):
        llm.respond("s", [UserTurn("q")], tools=[])


def test_claude_api_client_keeps_automatic_caching():
    fake = FakeBedrock(response([block(type="text", text="x")]))
    llm = AnthropicLLM.__new__(AnthropicLLM)
    llm.model, llm.max_tokens, llm.client = "claude-haiku-4-5", 100, fake
    llm.respond("s", [UserTurn("q")], tools=[])
    assert fake.requests[0]["cache_control"] == {"type": "ephemeral"}


def test_provider_setting_selects_bedrock(monkeypatch):
    made = {}

    class StubBedrock:
        def __init__(self, **kw):
            made.update(kw)

    monkeypatch.setattr(anthropic, "AnthropicBedrock", StubBedrock)
    llm = make_llm(Settings(provider="bedrock", bedrock_model_id=MODEL, aws_region="us-east-1"))
    assert isinstance(llm, BedrockLLM) and llm.name == "bedrock" and llm.model == MODEL
    assert made["aws_region"] == "us-east-1"


@pytest.mark.parametrize("model, expected", [
    ("global.anthropic.claude-haiku-4-5-20251001-v1:0", "claude-haiku-4-5"),
    ("us.anthropic.claude-haiku-4-5-20251001-v1:0", "claude-haiku-4-5"),
    ("anthropic.claude-sonnet-4-6", "claude-sonnet-4-6"),
    ("claude-haiku-4-5", "claude-haiku-4-5"),
    ("demo-rules", "demo-rules"),
])
def test_bedrock_ids_map_to_base_models(model, expected):
    assert canonical_model(model) == expected
