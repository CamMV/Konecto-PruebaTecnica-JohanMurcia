"""Tokenization shared by the documents and the queries of the lexical index.

Postgres's `simple` parser splits part numbers in unhelpful ways ('-11300000', '/a'), so
we pre-tokenize here and store clean alphanumeric tokens in `search_document.lexical_text`.

For each word we generate:
  - its alphanumeric form:   '761A00-11300000/A' -> '761a0011300000a', 'on/off' -> 'onoff'
  - its pieces (only if the word has letters): '761a00', '11300000', 'on', 'off'
  - the model code if it looks like a Series 76 ID: '761a'
"""

from __future__ import annotations

import re

MODEL_CODE_RE = re.compile(r"^76\d[a-z]")
STOPWORDS = {
    # en
    "a", "an", "the", "of", "for", "and", "or", "to", "in", "is", "are", "what", "which", "with",
    "me", "i", "it", "its", "on", "at", "by", "be", "do", "does", "need", "about", "tell", "that",
    "this", "how", "much", "many", "any", "can", "you", "my", "we", "our", "show", "give", "least",
    # es
    "el", "la", "los", "las", "de", "del", "un", "una", "y", "o", "que", "con", "para", "por",
    "es", "en", "al", "se", "mi", "necesito", "cual", "cuál", "qué", "como", "cómo",
}


def lexical_tokens(text: str) -> list[str]:
    tokens: list[str] = []
    for word in text.lower().split():
        word = word.strip(".,;:()[]{}\"'¿?¡!")
        number = word.replace(",", "")
        if re.fullmatch(r"\d+\.\d+", number):  # '11.2' stays '11.2' (it doesn't collide with 112)
            tokens.append(number)
            continue
        full = re.sub(r"[^0-9a-z]", "", word)
        if not full:
            continue
        candidates = [full]
        if re.search(r"[a-z]", word):
            pieces = [p for p in re.split(r"[^0-9a-z]+", word) if p]
            if len(pieces) > 1:
                candidates += pieces
        if MODEL_CODE_RE.match(full):
            candidates.append(full[:4])
        tokens += [t for t in candidates if len(t) > 1 and t not in STOPWORDS]
    return tokens


def unique(tokens: list[str]) -> list[str]:
    return list(dict.fromkeys(tokens))
