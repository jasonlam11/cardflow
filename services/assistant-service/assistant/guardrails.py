"""
Deterministic checks around the model. They don't trust the model to behave:
1. redact()             personal data never reaches the LLM (user text and tool results)
2. parse_citations()    answers must cite sources the server actually provided
3. ungrounded_amounts() every dollar amount in an answer must appear in those sources
"""

import re
from dataclasses import dataclass

REFUSAL_PREFIXES = ("i don't know", "i can't", "i cannot")

_CARD = re.compile(r"\b\d(?:[ -]?\d){12,18}\b")  # starts and ends on a digit
_EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")
_SSN = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
_PHONE = re.compile(r"(?<!\d)(?:\+?1[ .-]?)?\(?\d{3}\)?[ .-]?\d{3}[ .-]?\d{4}(?!\d)")
_CITATION = re.compile(r"\[(doc|tool):([a-z0-9_#-]+)\]")
_AMOUNT = re.compile(r"\$\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?")


def _luhn(digits: str) -> bool:
    total, parity = 0, len(digits) % 2
    for i, ch in enumerate(digits):
        d = int(ch)
        if i % 2 == parity:
            d = d * 2 - 9 if d * 2 > 9 else d * 2
        total += d
    return total % 10 == 0


def redact(text: str) -> str:
    """Masks card numbers (Luhn-valid only), SSNs, emails and phone numbers."""
    def card(m: re.Match) -> str:
        digits = re.sub(r"\D", "", m.group(0))
        return "[REDACTED_CARD]" if 13 <= len(digits) <= 19 and _luhn(digits) else m.group(0)

    text = _CARD.sub(card, text)
    text = _SSN.sub("[REDACTED_SSN]", text)
    text = _EMAIL.sub("[REDACTED_EMAIL]", text)
    return _PHONE.sub("[REDACTED_PHONE]", text)


@dataclass(frozen=True)
class Citation:
    kind: str  # "doc" or "tool"
    ref: str   # chunk id or tool name

    def __str__(self) -> str:
        return f"[{self.kind}:{self.ref}]"


def parse_citations(answer: str) -> list[Citation]:
    seen, out = set(), []
    for kind, ref in _CITATION.findall(answer):
        c = Citation(kind, ref)
        if c not in seen:
            seen.add(c)
            out.append(c)
    return out


def invalid_citations(citations: list[Citation], doc_ids: set[str], tools_called: set[str]) -> list[Citation]:
    """Citations that don't point at a section we retrieved or a tool we ran for THIS request."""
    return [c for c in citations
            if not ((c.kind == "doc" and c.ref in doc_ids) or (c.kind == "tool" and c.ref in tools_called))]


def amounts_in(text: str) -> set[int]:
    """Dollar amounts as integer cents."""
    out = set()
    for whole, cents in _AMOUNT.findall(text):
        out.add(int(whole.replace(",", "")) * 100 + int(cents or 0))
    return out


def ungrounded_amounts(answer: str, sources: list[str]) -> set[int]:
    """Amounts the answer states that appear in no source: likely hallucinated numbers."""
    allowed: set[int] = set()
    for s in sources:
        allowed |= amounts_in(s)
    return amounts_in(answer) - allowed


def is_refusal(answer: str) -> bool:
    """The model was told to start with "I don't know" (can't ground it) or "I can't" (out of scope)."""
    return answer.strip().lower().replace("\u2019", "'").startswith(REFUSAL_PREFIXES)
