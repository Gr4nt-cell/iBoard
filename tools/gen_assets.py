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
        fix_contractions(lang, seen)
        words = sorted(seen.items(), key=lambda kv: -kv[1])[:MAX_WORDS]
        with open(os.path.join(out_dir, f"{lang}.txt"), "w", encoding="utf-8") as f:
            for w, c in words:
                f.write(f"{w}\t{c}\n")
        print(lang, len(words), "words")


# The subtitle corpus splits contractions ("don't" -> "don" + "t") and keeps apostrophe-less
# misspellings ("dont"). Rebuild the real forms so predictions and autocorrect use them.
EN_NT_STEMS = ["don", "didn", "doesn", "couldn", "wouldn", "shouldn", "isn", "aren", "wasn", "weren",
               "hasn", "haven", "hadn", "mustn", "needn", "mightn"]
# Contractions whose stem is also a real word: frequency relative to "don't".
EN_SHARED = {"I'm": .95, "it's": .9, "that's": .55, "can't": .5, "you're": .42, "I'll": .36, "what's": .3,
             "let's": .27, "he's": .27, "there's": .22, "we're": .22, "I've": .21, "she's": .17, "they're": .17,
             "won't": .16, "I'd": .15, "ain't": .08, "you'll": .1, "we'll": .1, "you've": .08, "he'll": .05,
             "she'll": .03, "they'll": .04, "we've": .05, "they've": .04, "you'd": .05, "he'd": .03, "we'd": .03,
             "who's": .06, "where's": .04, "how's": .03, "here's": .06, "y'all": .02, "o'clock": .03}
EN_DROP = ["dont", "didnt", "doesnt", "couldnt", "wouldnt", "shouldnt", "isnt", "arent", "wasnt", "werent",
           "hasnt", "havent", "hadnt", "mustnt", "cant", "wont", "im", "youre", "thats", "whats", "theyre",
           "ive", "hes", "shes", "theres", "youve", "youll", "ain", "aint", "dunno"]
FR_ADD = {"c'est": .9, "j'ai": .55, "n'est": .25, "d'accord": .2, "qu'il": .15, "s'il": .12, "c'était": .12,
          "aujourd'hui": .06, "j'étais": .05, "qu'elle": .05, "l'on": .03, "j'aime": .04, "t'es": .04}


def fix_contractions(lang, seen):
    if lang == "en":
        dont = seen.get("don", 1000000)
        for stem in EN_NT_STEMS:
            if stem in seen:
                seen[stem + "'t"] = seen.get(stem + "'t", 0) + seen.pop(stem)
        for word, share in EN_SHARED.items():
            seen[word] = max(seen.get(word, 0), int(dont * share))
        for w in EN_DROP:
            seen.pop(w, None)
    elif lang == "fr":
        base = seen.get("est", 1000000)
        for word, share in FR_ADD.items():
            seen[word] = max(seen.get(word, 0), int(base * share))


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
