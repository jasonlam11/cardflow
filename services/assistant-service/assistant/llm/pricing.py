"""USD per million tokens (Claude API list prices). Used to report the cost of each request and eval run."""

PRICES = {
    "claude-haiku-4-5": (1.00, 5.00),
    "claude-sonnet-5-5": (2.00, 10.00),
    "claude-opus-5-5": (4.00, 20.00),
}
CACHE_READ_MULTIPLIER = 0.1
CACHE_WRITE_MULTIPLIER = 1.25


def cost_usd(model: str, input_tokens: int, output_tokens: int, cache_read: int = 0, cache_write: int = 0) -> float:
    if model not in PRICES:
        return 0.0
    inp, out = PRICES[model]
    return (input_tokens * inp + cache_read * inp * CACHE_READ_MULTIPLIER + cache_write * inp * CACHE_WRITE_MULTIPLIER
            + output_tokens * out) / 1_000_000
