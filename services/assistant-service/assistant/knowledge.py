"""Loads the benefits documents and splits them into one chunk per "## " section."""

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Chunk:
    id: str        # stable citation id, e.g. "travel-insurance#trip-delay"
    doc: str       # document slug, e.g. "travel-insurance"
    title: str     # document title, e.g. "Travel Insurance"
    section: str   # section heading, e.g. "Trip delay"
    text: str      # section body

    def embedding_text(self) -> str:
        # Title + heading give short sections enough context to match questions
        return f"{self.title} - {self.section}\n{self.text}"


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def chunk_document(path: Path) -> list[Chunk]:
    lines = path.read_text(encoding="utf-8").splitlines()
    doc = path.stem
    title = next((l[2:].strip() for l in lines if l.startswith("# ")), doc)
    chunks: list[Chunk] = []
    section, body = None, []

    def flush():
        text = "\n".join(l for l in body if not l.startswith(">")).strip()
        if section and text:
            chunks.append(Chunk(f"{doc}#{slug(section)}", doc, title, section, text))

    for line in lines:
        if line.startswith("## "):
            flush()
            section, body = line[3:].strip(), []
        elif section is not None:
            body.append(line)
    flush()
    return chunks


def load_chunks(docs_dir: Path) -> list[Chunk]:
    chunks = [c for path in sorted(docs_dir.glob("*.md")) for c in chunk_document(path)]
    ids = [c.id for c in chunks]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate section headings produce duplicate citation ids")
    return chunks


def corpus_hash(chunks: list[Chunk]) -> str:
    """Changes whenever any chunk changes, so the index is rebuilt only when needed."""
    h = hashlib.sha256()
    for c in chunks:
        h.update(c.id.encode())
        h.update(c.embedding_text().encode())
    return h.hexdigest()[:16]
