#!/usr/bin/env python3
"""Builds iBoard's Chinese pinyin table: assets/dict/zh_pinyin.tsv ("pinyin<TAB>word<TAB>freq").

Usage: python3 tools/build_pinyin.py <jieba dict.txt> <python-pinyin checkout>

Sources (open data):
  - fxsjy/jieba jieba/dict.txt (word frequencies), MIT
  - mozillazg/python-pinyin (readings), MIT
Pinyin is toneless, with ü written as v (lv = 绿), as typed on a QWERTY keyboard.
"""
import os, re, sys
sys.path.insert(0, sys.argv[2])
from pypinyin import lazy_pinyin, Style

HAN = re.compile(r"^[一-鿿]+$")
MAX_WORDS = 110000
rows = {}
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        p = line.split()
        if len(p) < 2 or not HAN.match(p[0]) or len(p[0]) > 6:
            continue
        rows[p[0]] = rows.get(p[0], 0) + int(p[1])
words = sorted(rows.items(), key=lambda kv: -kv[1])
singles = [w for w in words if len(w[0]) == 1]
multi = [w for w in words if len(w[0]) > 1][:MAX_WORDS - len(singles)]
out = []
for w, n in singles + multi:
    py = "".join(lazy_pinyin(w, style=Style.NORMAL, errors="ignore"))
    if py and re.fullmatch(r"[a-z]+", py):
        out.append((py, w, n))
        # A few common characters have a second everyday reading.
        if len(w) == 1:
            from pypinyin import pinyin
            for alt in pinyin(w, style=Style.NORMAL, heteronym=True)[0][1:3]:
                if re.fullmatch(r"[a-z]+", alt) and alt != py:
                    out.append((alt, w, max(1, n // 4)))
out.sort(key=lambda r: (r[0], -r[2]))
here = os.path.dirname(os.path.abspath(__file__))
dest = os.path.join(here, "..", "app", "src", "main", "assets", "dict", "zh_pinyin.tsv")
with open(dest, "w", encoding="utf-8") as f:
    for py, w, n in out:
        f.write(f"{py}\t{w}\t{n}\n")
print(len(out), "entries", os.path.getsize(dest), "bytes")
