#!/usr/bin/env python3
"""Tokenise the corpus, build the 32k vocabulary and write train/valid/test id streams.

Usage: prep.py DATA_DIR OUT_DIR [--vocab 32000]
Writes: vocab.tsv (id, lower, display, count), train.u16, valid.u16, test.u16, bench_en.txt
"""
import collections, os, sys, zlib, multiprocessing as mp
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import tok

data, out = sys.argv[1], sys.argv[2]
V = int(sys.argv[sys.argv.index("--vocab") + 1]) if "--vocab" in sys.argv else 32000
os.makedirs(out, exist_ok=True)
# keep-probability per source (out of 1000) and upsampling; conversational text counts more
KEEP = {"tatoeba.txt": 1000, "hh.txt": 380, "oasst.txt": 1000, "smoltalk.txt": 1000, "web.txt": 440}
REPEAT = {"smoltalk.txt": 3}


def h(line): return zlib.crc32(line.encode("utf-8"))


def split_of(line):
    r = h(line) % 1000
    return "test" if r < 3 else "valid" if r < 6 else "train"


def lines_of(name):
    seen = set()
    with open(os.path.join(data, name), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line in seen: continue
            seen.add(line)
            if (h(line + "k") % 1000) >= KEEP[name]: continue
            yield line


def count_chunk(lines):
    c = collections.Counter(); cap = collections.Counter(); mid = collections.Counter()
    surf = collections.defaultdict(collections.Counter)
    for line in lines:
        prev_start = True
        for kind, s in tok.tokens(line):
            if kind == "w":
                w = s.lower(); c[w] += 1
                if not prev_start:
                    mid[w] += 1
                    if s[0].isupper(): cap[w] += 1; surf[w][s] += 1
                prev_start = False
            elif kind == "s": prev_start = True
            else: prev_start = prev_start and False
    return c, cap, mid, {w: dict(v) for w, v in surf.items()}


def chunks(it, n=20000):
    buf = []
    for x in it:
        buf.append(x)
        if len(buf) >= n: yield buf; buf = []
    if buf: yield buf


def all_lines():
    for name in KEEP:
        for line in lines_of(name): yield name, line


def pass1():
    C = collections.Counter(); CAP = collections.Counter(); MID = collections.Counter()
    SURF = collections.defaultdict(collections.Counter)
    with mp.Pool(2) as pool:
        for c, cap, mid, surf in pool.imap_unordered(count_chunk, chunks(l for _, l in all_lines()), 4):
            C.update(c); CAP.update(cap); MID.update(mid)
            for w, d in surf.items(): SURF[w].update(d)
    return C, CAP, MID, SURF


_INDEX = None


def encode_chunk(items):
    res = {"train": [], "valid": [], "test": []}
    for name, line in items:
        ids = tok.encode_line(line, _INDEX)
        if len(ids) < 3: continue
        s = split_of(line)
        res[s].append(np.array(ids, dtype=np.uint16))
        if s == "train":
            for _ in range(REPEAT.get(name, 1) - 1): res[s].append(np.array(ids, dtype=np.uint16))
    return {k: np.concatenate(v) if v else np.zeros(0, np.uint16) for k, v in res.items()}


def init(index):
    global _INDEX; _INDEX = index


if __name__ == "__main__":
    print("pass 1: counting", flush=True)
    C, CAP, MID, SURF = pass1()
    words = [(w, n) for w, n in C.most_common() if len(w) <= tok.MAX_WORD][: V - len(tok.SPECIALS)]
    index = {w: i + len(tok.SPECIALS) for i, (w, _) in enumerate(words)}
    total = sum(C.values()); covered = sum(n for _, n in words)
    print(f"tokens {total:,}  vocab {len(index):,}  coverage {covered/total:.4f}", flush=True)
    with open(os.path.join(out, "vocab.tsv"), "w", encoding="utf-8") as f:
        for i, s in enumerate(tok.SPECIALS): f.write(f"{i}\t{s}\t{s}\t0\n")
        for w, n in words:
            disp = w
            if MID[w] >= 5 and CAP[w] / MID[w] >= 0.75 and SURF[w]:
                disp = SURF[w].most_common(1)[0][0]
            if w == "i" or w.startswith("i'"): disp = "I" + w[1:]
            f.write(f"{index[w]}\t{w}\t{disp}\t{n}\n")
    print("pass 2: encoding", flush=True)
    acc = {"train": [], "valid": [], "test": []}
    with mp.Pool(2, initializer=init, initargs=(index,)) as pool:
        for part in pool.imap(encode_chunk, chunks(all_lines(), 20000), 4):
            for k, v in part.items(): acc[k].append(v)
    for k, v in acc.items():
        arr = np.concatenate(v); arr.tofile(os.path.join(out, f"{k}.u16"))
        print(k, f"{len(arr):,} tokens", flush=True)
    # conversational held-out sentences for the on-device benchmark (raw text, original case)
    import random
    rnd = random.Random(7); bench = []
    for name in ("tatoeba.txt", "oasst.txt", "smoltalk.txt", "hh.txt"):
        for line in lines_of(name):
            if split_of(line) == "test" and 20 <= len(line) <= 90 and line[-1] in ".?!" and line.count(".") + line.count("?") + line.count("!") == 1:
                bench.append(line)
    rnd.shuffle(bench)
    with open(os.path.join(out, "bench_en.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(bench[:3000]) + "\n")
    print("bench sentences", min(len(bench), 3000))
