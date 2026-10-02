# 12. Provider-agnostic LLM interface with deterministic guardrails around it

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
The assistant must be grounded, cite sources, refuse what it can't ground, resist prompt injection, keep personal data away from the model, and never move money. Models are probabilistic; these requirements can't rest on prompt wording alone. The plan also calls for swapping providers (Claude API now, Amazon Bedrock in Phase 7), and the project currently has **no API key**.

## Decision
**One small interface, several implementations.** `LLMClient.respond(system, turns, tools) -> LLMResponse` over a neutral conversation shape (user turn, assistant turn with tool calls, tool results). Implementations:
- `AnthropicLLM`: the official `anthropic` SDK; default `claude-haiku-4-5` (the owner's choice; one env var to change). Manual tool loop, all tool results returned in one message per turn, `stop_reason == "refusal"` checked before reading content, typed SDK errors mapped to "assistant unavailable".
- `DemoLLM`: deterministic rules, used when no credentials exist and in CI. Clearly labelled "demo mode" in the API and UI; its eval numbers measure the pipeline, not a model.
- `ScriptedLLM`: replays chosen responses, to test guardrails against a misbehaving model.
- Bedrock (Phase 7) slots in behind the same interface.

The loop is hand-written rather than the SDK's beta tool runner because the interface must work for every provider and the guardrails run between turns.

**Guardrail layers (code, not prompt):**
| Layer | Mechanism |
|---|---|
| Capability | Only 4 read-only tools exist; none moves money. The card id is bound from the session, never a tool parameter. |
| Personal data | Card numbers (Luhn-checked), SSNs, emails and phones are redacted from the question **and from tool results** before anything reaches the model. Logs hold metadata only. |
| Grounding | Every answer must cite `[doc:...]` or `[tool:...]` that was **actually provided in this request**. Uncited or wrongly cited answers are replaced with "I don't know". |
| Numbers | Every dollar amount in the answer must appear in this request's sources or tool results; otherwise replaced. |
| Bounds | At most 5 model rounds; tool input validation (dates, ranges, limits); 2,000-character questions; 30 s LLM timeout. |
| Injection | Sources, tool output and merchant names are marked as data in the system prompt; the eval includes injections from the user and from data (a merchant named "IGNORE ALL PREVIOUS INSTRUCTIONS…"). |

**Prompt caching:** the system prompt is byte-stable (no dates or ids; the date goes in the user turn) and top-level automatic caching is on. Haiku 4.5 only caches prefixes of 4,096+ tokens and ours is ~2k, so on Haiku the eval is expected to show zero cache reads; larger models cache at 512–1,024 tokens.

## Consequences
- The guardrails are unit-tested against scripted bad behaviour, independent of any model.
- Guardrails can be too strict: a correct answer that forgets a citation, or that sums two amounts itself, is withheld. The eval's false-refusal rate measures this cost.
- Without an API key, model-quality metrics (accuracy, refusal correctness on a real model) are not yet measured. The real-model eval needs credentials **and** `--confirm-cost`, and runs only on demand (locally or a manual CI job), never on every push.
