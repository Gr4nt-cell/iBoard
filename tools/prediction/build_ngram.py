#!/usr/bin/env python3
"""Build the pruned Kneser-Ney n-gram file (bigram, trigram, 4-gram) read by engine/NgramModel.kt.

For each order the file stores, per context, the top-K continuations with their discounted
Kneser-Ney probability (continuation counts for the lower orders, raw counts for the highest)
and a quantised context total, so the keyboard can interpolate orders at run time.

Usage: build_ngram.py PREP_DIR OUT_FILE [--max-mb 14]
Layout (little endian): "IBNG", u32 version, u32 V, u32 orders,
  per order: u32 contextLength, u32 nContexts, u32 nEntries,
             u64[nContexts] keys (ids packed 16 bits each, sorted), u8[nContexts] total, u32[nContexts+1] offsets,
             u16[nEntries] word ids, u16[nEntries] cost = round(-ln p * 3000)
"""
import os, struct, sys
import numpy as np

prep, out = sys.argv[1], sys.argv[2]
MIN = {2: 2, 3: 3, 4: 3}     # minimum (continuation) count for a stored entry
TOPK = {2: 12, 3: 8, 4: 6}
SPECIAL = 5                  # ids below this are never suggested


def keys_for(a, n):
    k = a[: len(a) - n + 1].astype(np.uint64)
    for i in range(1, n):
        k = (k << np.uint64(16)) | a[i: len(a) - n + 1 + i].astype(np.uint64)
    return k


a = np.fromfile(os.path.join(prep, "train.u16"), dtype=np.uint16)
V = int(a.max()) + 1
print("tokens", len(a), flush=True)
u4, c4 = np.unique(keys_for(a, 4), return_counts=True); print("4-grams", len(u4), flush=True)
u3raw, c3raw = np.unique(keys_for(a, 3), return_counts=True); print("3-grams", len(u3raw), flush=True)
u2raw, c2raw = np.unique(keys_for(a, 2), return_counts=True); print("2-grams", len(u2raw), flush=True)
del a
M48 = np.uint64((1 << 48) - 1); M32 = np.uint64((1 << 32) - 1)
# continuation counts: how many distinct left words precede the lower-order n-gram
t3, n3 = np.unique(u4 & M48, return_counts=True)
t2, n2 = np.unique(u3raw & M32, return_counts=True)
# trigrams/bigrams that never occur inside a longer n-gram keep their raw count
def merge(raw_keys, raw_counts, cont_keys, cont_counts):
    d = raw_counts.astype(np.float64).copy()
    idx = np.searchsorted(raw_keys, cont_keys)
    ok = (idx < len(raw_keys)) & (raw_keys[np.minimum(idx, len(raw_keys) - 1)] == cont_keys)
    d[idx[ok]] = cont_counts[ok]
    return d
cnt3 = merge(u3raw, c3raw, t3, n3)
cnt2 = merge(u2raw, c2raw, t2, n2)
tables = {4: (u4, c4.astype(np.float64)), 3: (u3raw, cnt3), 2: (u2raw, cnt2)}
parts = [b"IBNG", struct.pack("<3I", 1, V, 3)]
total_bytes = 0
for order in (2, 3, 4):
    keys, cnt = tables[order]
    n1 = (cnt == 1).sum(); n2c = (cnt == 2).sum(); D = n1 / (n1 + 2.0 * n2c)
    ctx = keys >> np.uint64(16); w = (keys & np.uint64(0xFFFF)).astype(np.int64)
    starts = np.flatnonzero(np.r_[True, ctx[1:] != ctx[:-1]])
    total = np.add.reduceat(cnt, starts)
    group = np.repeat(np.arange(len(starts)), np.diff(np.r_[starts, len(ctx)]))
    p = np.maximum(cnt - D, 0) / total[group]
    keep = (cnt >= MIN[order]) & (w >= SPECIAL)
    idx = np.flatnonzero(keep)
    o = idx[np.lexsort((-p[idx], group[idx]))]               # by context, best first
    g = group[o]; first = np.r_[True, g[1:] != g[:-1]]
    pos = np.arange(len(o)); rank = pos - np.maximum.accumulate(np.where(first, pos, 0))
    o = o[rank < TOPK[order]]
    g = group[o]
    uniq_g, cstart = np.unique(g, return_index=True)         # contexts that still have entries
    nctx = len(uniq_g)
    ckeys = ctx[starts[uniq_g]].astype(np.uint64)
    ctotal = np.clip(np.round(np.log2(total[uniq_g] + 1) * 8), 0, 255).astype(np.uint8)
    offsets = np.r_[cstart, len(o)].astype(np.uint32)
    words = w[o].astype(np.uint16)
    cost = np.clip(np.round(-np.log(p[o]) * 3000), 0, 65535).astype(np.uint16)
    print(f"order {order}: D={D:.3f} contexts {nctx:,} entries {len(o):,}", flush=True)
    blob = struct.pack("<3I", order - 1, nctx, len(o)) + ckeys.astype("<u8").tobytes() + ctotal.tobytes() + offsets.astype("<u4").tobytes() + words.astype("<u2").tobytes() + cost.astype("<u2").tobytes()
    parts.append(blob); total_bytes += len(blob)
with open(out, "wb") as f: f.write(b"".join(parts))
print("wrote", out, f"{total_bytes/1e6:.1f} MB", flush=True)
