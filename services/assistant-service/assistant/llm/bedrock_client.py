"""
Claude on Amazon Bedrock, through the official Anthropic SDK's Bedrock client.

Same request/response shape as the Claude API, so everything except the client and the
caching flag is inherited from AnthropicLLM. Credentials come from the AWS default chain:
on EC2 that is the instance role (temporary credentials from the metadata service), so
there is no API key anywhere. Needs the `anthropic[bedrock]` extra (requirements-bedrock.txt),
which only the AWS image installs.

Haiku 4.5 is served on Bedrock's InvokeModel path (not the newer Messages endpoint), hence
AnthropicBedrock and a cross-region inference profile id like
"global.anthropic.claude-haiku-4-5-20251001-v1:0" (global = no regional price premium).
"""

import logging

from .anthropic_client import AnthropicLLM
from .base import LLMResponse, LLMUnavailable, Turn

log = logging.getLogger("assistant.llm")


class BedrockLLM(AnthropicLLM):
    name = "bedrock"
    # Bedrock rejects the top-level cache_control field (explicit breakpoints only); our prefix
    # is below Haiku 4.5's 4,096-token caching minimum anyway
    auto_cache = False

    def __init__(self, model: str, region: str, max_tokens: int = 4096, timeout_s: float = 30.0, client=None):
        self.model = model
        self.max_tokens = max_tokens
        if client is None:
            from anthropic import AnthropicBedrock

            client = AnthropicBedrock(aws_region=region, timeout=timeout_s, max_retries=2)
        self.client = client

    def respond(self, system: str, turns: list[Turn], tools: list[dict]) -> LLMResponse:
        try:
            return super().respond(system, turns, tools)
        except LLMUnavailable:
            raise
        except Exception as e:
            # AWS-side failures (no credentials, signing errors, endpoint unreachable) come from
            # botocore rather than the Anthropic SDK; they mean "unavailable", not a bug in our request
            if type(e).__module__.startswith("botocore"):
                raise LLMUnavailable(f"Bedrock unavailable ({type(e).__name__})") from e
            raise
