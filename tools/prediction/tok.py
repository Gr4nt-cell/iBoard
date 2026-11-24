"""Tokenizer shared by every training script. The Kotlin keyboard code (engine/Tokenizer.kt)
implements exactly the same rules, so training and typing see identical tokens.

Rules: curly apostrophes -> '; letters (with inner apostrophes) are words, lower-cased for ids;
digit runs -> <num>; , ; : -> ","; . ! ? … and newlines -> <s> (sentence start); everything
else (emoji, quotes, brackets, symbols) is ignored. Words longer than 24 letters are <unk>.
"""
import re

PAD, UNK, BOS, NUM, COMMA = 0, 1, 2, 3, 4
SPECIALS = ["<pad>", "<unk>", "<s>", "<num>", ","]
MAX_WORD = 24

_TOKEN = re.compile(r"([^\W\d_]+(?:'[^\W\d_]+)*)|(\d+(?:[.,:]\d+)*)|([,;:])|([.!?…\n]+)")


def tokens(text):
    """Yield (kind, surface) with kind in w (word), n (number), c (comma), s (sentence break)."""
    text = text.replace("’", "'").replace("‘", "'").replace("`", "'")
    for m in _TOKEN.finditer(text):
        if m.group(1): yield "w", m.group(1)
        elif m.group(2): yield "n", m.group(2)
        elif m.group(3): yield "c", m.group(3)
        else: yield "s", m.group(4)


def encode_line(text, index):
    """Token ids for one line; always starts with <s>; index maps lower-case word -> id."""
    out = [BOS]
    for kind, surface in tokens(text):
        if kind == "w":
            w = surface.lower()
            out.append(index.get(w, UNK) if len(w) <= MAX_WORD else UNK)
        elif kind == "n": out.append(NUM)
        elif kind == "c":
            if out[-1] != BOS: out.append(COMMA)
        else:
            if out[-1] != BOS: out.append(BOS)
    if out[-1] == BOS and len(out) > 1: out.pop()
    return out
