from assistant.knowledge import corpus_hash, slug


def test_every_section_becomes_a_chunk_with_a_stable_id(chunks):
    assert len(chunks) == 36
    ids = [c.id for c in chunks]
    assert len(ids) == len(set(ids))
    assert "travel-insurance#trip-delay" in ids
    assert "rewards#earning-rates" in ids


def test_disclaimer_is_not_part_of_any_chunk(chunks):
    assert all("Fictional benefits" not in c.text for c in chunks)


def test_hash_changes_only_when_content_changes(chunks):
    assert corpus_hash(chunks) == corpus_hash(list(chunks))
    edited = list(chunks)
    edited[0] = edited[0].__class__(edited[0].id, edited[0].doc, edited[0].title, edited[0].section, edited[0].text + "!")
    assert corpus_hash(edited) != corpus_hash(chunks)


def test_slug():
    assert slug("What trip cancellation does not cover") == "what-trip-cancellation-does-not-cover"
