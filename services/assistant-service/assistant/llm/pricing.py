"""USD per million tokens (Claude API list prices). Used to report the cost of each request and eval run."""

import re

PRICES = {
    "claude-haiku-4-5": (1.00, 5.00),
    "claude-sonnet-5-5": (2.00, 10.00),
    "claude-opus-5-5": (4.00, 20.00),
}
CACHE_READ_MULTIPLIER = 0.1
CACHE_WRITE_MULTIPLIER = 1.25


_BEDROCK_ID = re.compile(r"^(?:(?:global|us|eu|apac|jp)\.)?anthropic\.(claude-[a-z]+-\d+-\d+)(?:-\d{8})?(?:-v\d+:\d+)?$")


def canonical_model(model: str) -> str:
    """'global.anthropic.claude-haiku-4-5-20251001-v1:0' (Bedrock) -> 'claude-haiku-4-5'."""
    m = _BEDROCK_ID.match(model)
    return m.group(1) if m else model


def cost_usd(model: str, input_tokens: int, output_tokens: int, cache_read: int = 0, cache_write: int = 0) -> float:
    """Estimate at Claude API list prices. Bedrock bills through AWS at its own rates (global
    inference profiles have no regional premium), so treat Bedrock figures as an estimate."""
    model = canonical_model(model)
    if model not in PRICES:
        return 0.0
    inp, out = PRICES[model]
    return (input_tokens * inp + cache_read * inp * CACHE_READ_MULTIPLIER + cache_write * inp * CACHE_WRITE_MULTIPLIER
            + output_tokens * out) / 1_000_000
