from assistant import guardrails as g


def test_redacts_luhn_valid_card_numbers_but_not_other_numbers():
    assert g.redact("my card 4242 4242 4242 4242 was charged") == "my card [REDACTED_CARD] was charged"
    assert g.redact("order 1234567890123") == "order 1234567890123"  # 13 digits, fails Luhn


def test_redacts_ssn_email_phone():
    out = g.redact("SSN 123-45-6789, mail me at jo@example.com or call (617) 555-0142")
    assert "123-45-6789" not in out and "jo@example.com" not in out and "555-0142" not in out
    assert "[REDACTED_SSN]" in out and "[REDACTED_EMAIL]" in out and "[REDACTED_PHONE]" in out


def test_parses_citations_once_each():
    cites = g.parse_citations("A [doc:rewards#earning-rates]. B [tool:get_balance]. C [doc:rewards#earning-rates].")
    assert [str(c) for c in cites] == ["[doc:rewards#earning-rates]", "[tool:get_balance]"]


def test_flags_citations_not_provided_in_this_request():
    cites = g.parse_citations("x [doc:rewards#earning-rates] y [doc:made-up#section] z [tool:get_balance]")
    bad = g.invalid_citations(cites, doc_ids={"rewards#earning-rates"}, tools_called=set())
    assert [str(c) for c in bad] == ["[doc:made-up#section]", "[tool:get_balance]"]


def test_amounts_are_compared_in_cents():
    assert g.amounts_in("$5,000 and $500.00 and $0.65") == {500_000, 50_000, 65}


def test_ungrounded_amounts():
    sources = ["Coverage is up to $5,000 per trip", '{"balance": "$1,234.56"}']
    assert g.ungrounded_amounts("You're covered up to $5,000 and owe $1,234.56", sources) == set()
    assert g.ungrounded_amounts("You're covered up to $7,500", sources) == {750_000}


def test_refusal_prefixes():
    assert g.is_refusal("I don't know. Nothing about that.")
    assert g.is_refusal("I can't move money for you.")
    assert g.is_refusal("I can’t do that")  # curly apostrophe
    assert not g.is_refusal("Yes, you're covered.")
