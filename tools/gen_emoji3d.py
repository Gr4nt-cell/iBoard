#!/usr/bin/env python3
"""Converts Microsoft Fluent Emoji (MIT) 3D art into small WebP assets keyed by codepoints.

Usage: python3 tools/gen_emoji3d.py <fluentui-emoji checkout>
Output: app/src/main/assets/emoji3d/<codepoints>.webp and emoji_keywords.tsv
"""
import json, os, sys, glob
from PIL import Image

SIZE = 88
TONES = {"Default": None, "Light": "1f3fb", "Medium-Light": "1f3fc", "Medium": "1f3fd",
         "Medium-Dark": "1f3fe", "Dark": "1f3ff"}

def key(cps):
    return "_".join(c for c in cps.lower().split() if c != "fe0f")

JOBS = []

def save(src, dst):
    JOBS.append((src, dst))

def convert(job):
    src, dst = job
    if os.path.exists(dst):
        return
    im = Image.open(src).convert("RGBA")
    im.thumbnail((SIZE, SIZE), Image.LANCZOS)
    im.save(dst, "WEBP", quality=80, method=4)

root = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(here, "..", "app", "src", "main", "assets", "emoji3d")
os.makedirs(out, exist_ok=True)
kw = []
count = 0
for meta_path in sorted(glob.glob(os.path.join(root, "assets", "*", "metadata.json"))):
    folder = os.path.dirname(meta_path)
    meta = json.load(open(meta_path, encoding="utf-8"))
    base = meta.get("unicode")
    if not base:
        continue
    kw.append(f"{key(base)}\t{' '.join(meta.get('keywords', []))}")
    flat = glob.glob(os.path.join(folder, "3D", "*.png"))
    if flat:
        save(flat[0], os.path.join(out, key(base) + ".webp")); count += 1
        continue
    tones = meta.get("unicodeSkintones") or []
    for name, mod in TONES.items():
        pngs = glob.glob(os.path.join(folder, name, "3D", "*.png"))
        if not pngs:
            continue
        if mod is None:
            k = key(base)
        else:
            match = [t for t in tones if mod in t.split()]
            if not match:
                continue
            k = key(match[0])
        save(pngs[0], os.path.join(out, k + ".webp")); count += 1
from multiprocessing import Pool
with Pool(os.cpu_count()) as pool:
    pool.map(convert, JOBS, chunksize=16)
with open(os.path.join(here, "..", "app", "src", "main", "assets", "emoji_keywords.tsv"), "w", encoding="utf-8") as f:
    f.write("\n".join(kw) + "\n")
print(count, "images")
