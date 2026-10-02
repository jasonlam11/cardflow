"""
Provider-agnostic LLM interface. The conversation is kept in this neutral shape;
each client converts it to its own API format. Assistant turns also keep the
provider's raw content so it can be sent back exactly as received.
"""

from dataclasses import dataclass, field
from typing import Any, Protocol


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    input: dict


@dataclass(frozen=True)
class ToolResult:
    tool_call_id: str
    content: str
    is_error: bool = False


@dataclass
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    cost_usd: float = 0.0

    def add(self, other: "Usage") -> None:
        self.input_tokens += other.input_tokens
        self.output_tokens += other.output_tokens
        self.cache_read_tokens += other.cache_read_tokens
        self.cache_write_tokens += other.cache_write_tokens
        self.cost_usd += other.cost_usd


@dataclass
class UserTurn:
    text: str


@dataclass
class AssistantTurn:
    text: str
    tool_calls: list[ToolCall]
    provider_content: Any = None  # e.g. the Anthropic response.content list, replayed unchanged


@dataclass
class ToolResultsTurn:
    results: list[ToolResult]


Turn = UserTurn | AssistantTurn | ToolResultsTurn


@dataclass
class LLMResponse:
    text: str
    tool_calls: list[ToolCall]
    stop_reason: str  # "end_turn", "tool_use", "max_tokens", "refusal", ...
    usage: Usage = field(default_factory=Usage)
    provider_content: Any = None
    model: str = ""


class LLMUnavailable(Exception):
    """Provider down, rate limited after retries, auth problem: the assistant answers 503."""


class LLMClient(Protocol):
    name: str
    model: str

    def respond(self, system: str, turns: list[Turn], tools: list[dict]) -> LLMResponse: ...
