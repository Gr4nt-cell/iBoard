#!/usr/bin/env python3
"""Builds iBoard's bundled assets.

Usage: python3 tools/gen_assets.py <FrequencyWords checkout> <unicode-emoji-json checkout>

Sources (both open data):
  - hermitdave/FrequencyWords (content/2018/<lang>/<lang>_50k.txt), MIT / CC-BY-SA
  - muan/unicode-emoji-json (data-by-group.json), MIT
"""
import json
import os
import re
import sys
import unicodedata

LANGS = ["en", "es", "fr", "pt", "de", "ar"]
MAX_WORDS = 40000

LATIN = re.compile(r"^[^\W\d_]+(?:['\-][^\W\d_]+)*$", re.UNICODE)
ARABIC = re.compile(r"^[ء-غف-يٱ-ۓ]+$")
TASHKEEL = re.compile(r"[ً-ْـ]")
SINGLE_OK = {
    "en": set("ai"), "es": set("aeoyu"), "fr": set("aày"), "pt": set("aeoà"),
    "de": set(), "ar": set("وب"),
}


def build_dict(fw_root, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for lang in LANGS:
        path = os.path.join(fw_root, "content", "2018", lang, f"{lang}_50k.txt")
        seen = {}
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.strip().split(" ")
                if len(parts) != 2:
                    continue
                word, count = parts[0].strip(), int(parts[1])
                word = unicodedata.normalize("NFC", word)
                if lang == "ar":
                    word = TASHKEEL.sub("", word)
                    if not ARABIC.match(word):
                        continue
                else:
                    word = word.lower()
                    if not LATIN.match(word):
                        continue
                if len(word) == 1 and word not in SINGLE_OK[lang]:
                    continue
                if lang == "en" and word in ("i'm", "i'll", "i've", "i'd"):
                    word = "I" + word[1:]
                elif lang == "en" and word == "i":
                    word = "I"
                seen[word] = seen.get(word, 0) + count
        words = sorted(seen.items(), key=lambda kv: -kv[1])[:MAX_WORDS]
        with open(os.path.join(out_dir, f"{lang}.txt"), "w", encoding="utf-8") as f:
            for w, c in words:
                f.write(f"{w}\t{c}\n")
        print(lang, len(words), "words")


# iOS category order
GROUP_TO_CAT = {
    "Smileys & Emotion": 0, "People & Body": 0, "Animals & Nature": 1,
    "Food & Drink": 2, "Activities": 3, "Travel & Places": 4,
    "Objects": 5, "Symbols": 6, "Flags": 7,
}


def build_emoji(emoji_root, out_path):
    with open(os.path.join(emoji_root, "data-by-group.json"), encoding="utf-8") as f:
        data = json.load(f)
    groups = data if isinstance(data, list) else [
        {"name": k, "emojis": v} for k, v in data.items()]
    rows = []
    for order, g in enumerate(groups):
        cat = GROUP_TO_CAT.get(g["name"])
        if cat is None:
            continue
        for i, e in enumerate(g["emojis"]):
            rows.append((cat, order, i, e["emoji"], 1 if e.get("skin_tone_support") else 0,
                         e["name"].replace("\t", " ")))
    rows.sort(key=lambda r: (r[0], r[1], r[2]))
    with open(out_path, "w", encoding="utf-8") as f:
        for cat, _, _, emo, skin, name in rows:
            f.write(f"{cat}\t{emo}\t{skin}\t{name}\n")
    print(len(rows), "emoji")


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.join(here, "..", "app", "src", "main", "assets")
    build_dict(sys.argv[1], os.path.join(assets, "dict"))
    build_emoji(sys.argv[2], os.path.join(assets, "emoji.tsv"))
