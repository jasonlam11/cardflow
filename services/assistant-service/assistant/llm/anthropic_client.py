"""Claude through the official Anthropic Python SDK."""

import logging

import anthropic

from .base import AssistantTurn, LLMResponse, LLMUnavailable, ToolCall, ToolResultsTurn, Turn, Usage, UserTurn
from .pricing import cost_usd

log = logging.getLogger("assistant.llm")


def has_credentials() -> bool:
    """True if the SDK can authenticate (env key/token or an `ant auth login` profile)."""
    import os
    from pathlib import Path

    if os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"):
        return True
    return (Path.home() / ".config" / "anthropic").exists()


class AnthropicLLM:
    name = "anthropic"

    def __init__(self, model: str = "claude-haiku-4-5", max_tokens: int = 4096, timeout_s: float = 30.0):
        self.model = model
        self.max_tokens = max_tokens
        # Credentials come from the environment; never hardcoded
        self.client = anthropic.Anthropic(timeout=timeout_s, max_retries=2)

    def respond(self, system: str, turns: list[Turn], tools: list[dict]) -> LLMResponse:
        try:
            response = self.client.messages.create(
                model=self.model,
                max_tokens=self.max_tokens,
                system=system,
                tools=tools,
                messages=[self._message(t) for t in turns],
                # Automatic prompt caching of the stable prefix (system + tools). Note: Haiku 4.5 only
                # caches prefixes of 4096+ tokens, so this may report zero cache reads; the eval shows it.
                cache_control={"type": "ephemeral"},
            )
        except anthropic.AuthenticationError as e:
            raise LLMUnavailable("LLM credentials rejected") from e
        except anthropic.RateLimitError as e:
            raise LLMUnavailable("LLM rate limited") from e
        except anthropic.BadRequestError:
            raise  # our bug, not an outage: surface it
        except (anthropic.APIStatusError, anthropic.APIConnectionError, anthropic.APITimeoutError) as e:
            raise LLMUnavailable(f"LLM unavailable ({type(e).__name__})") from e

        u = response.usage
        usage = Usage(
            input_tokens=u.input_tokens, output_tokens=u.output_tokens,
            cache_read_tokens=u.cache_read_input_tokens or 0, cache_write_tokens=u.cache_creation_input_tokens or 0,
        )
        usage.cost_usd = cost_usd(self.model, usage.input_tokens, usage.output_tokens, usage.cache_read_tokens,
                                  usage.cache_write_tokens)

        if response.stop_reason == "refusal":
            # Always check before reading content: a refusal may carry partial or no text
            return LLMResponse("", [], "refusal", usage, response.content, response.model)

        text = "".join(b.text for b in response.content if b.type == "text")
        calls = [ToolCall(b.id, b.name, dict(b.input)) for b in response.content if b.type == "tool_use"]
        return LLMResponse(text, calls, response.stop_reason or "end_turn", usage, response.content, response.model)

    @staticmethod
    def _message(turn: Turn) -> dict:
        if isinstance(turn, UserTurn):
            return {"role": "user", "content": turn.text}
        if isinstance(turn, AssistantTurn):
            # Replay the model's own content blocks unchanged (tool_use ids must match)
            return {"role": "assistant", "content": turn.provider_content}
        if isinstance(turn, ToolResultsTurn):
            # All results for one assistant turn go back in ONE user message
            return {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": r.tool_call_id, "content": r.content, "is_error": r.is_error}
                for r in turn.results]}
        raise TypeError(f"unknown turn {turn!r}")
