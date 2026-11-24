#!/usr/bin/env python3
"""Build the tiny grammar table (word classes + class trigram transitions) used by GrammarFilter.

Trains an averaged-perceptron tagger on the Penn Treebank sample + CoNLL-2000 (NLTK corpora),
tags ~3M tokens of the iBoard training text, and exports (a) the 3 most likely word classes for
every vocabulary word and (b) P(class | previous two classes). Tokens with apostrophes
(don't, I'm, we'll...) and closed-class words are classed by rule, mirroring GrammarFilter.kt.

Usage: build_pos.py DATA_DIR PREP_DIR OUT_FILE      (NLTK_DATA must point at treebank + conll2000)
"""
import collections, os, random, struct, sys, zlib
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import tok
from nltk.corpus import treebank, conll2000
from nltk.tag.perceptron import PerceptronTagger

data, prep, out = sys.argv[1:4]
CLS = ["OTHER", "NOUN", "ADJ", "ADV", "PRON", "DET", "ADP", "CONJ", "NUM", "PRT", "VB", "VBZ", "VBD", "VBN", "VBG", "MD", "WH", "BE", "HAVE", "BOS", "COMMA"]
C = {n: i for i, n in enumerate(CLS)}; N = len(CLS)
BE = set("am is are was were be been being ain't i'm you're we're they're he's she's it's that's there's here's what's who's where's how's isn't aren't wasn't weren't".split())
HAVE = set("have has had having haven't hasn't hadn't i've you've we've they've should've would've could've might've must've who've".split())
MD = set("can could will would shall should may might must do does did can't couldn't won't wouldn't shouldn't mustn't shan't don't doesn't didn't needn't i'll you'll he'll she'll we'll they'll it'll that'll there'll i'd you'd he'd she'd we'd they'd it'd that'd gonna".split())
PRON = set("i you he she it we they me him her us them myself yourself himself herself itself ourselves themselves".split())
SURROGATE = {C["BE"]: "is", C["HAVE"]: "has", C["MD"]: "will"}


def rule_class(w):
    if w in BE: return C["BE"]
    if w in HAVE: return C["HAVE"]
    if w in MD: return C["MD"]
    if w in PRON: return C["PRON"]
    if w == "to": return C["PRT"]
    return None


def ptb(tag):
    if tag.startswith("NN"): return C["NOUN"]
    if tag.startswith("JJ"): return C["ADJ"]
    if tag.startswith("RB"): return C["ADV"]
    if tag in ("PRP",): return C["PRON"]
    if tag in ("DT", "PDT", "PRP$"): return C["DET"]
    if tag == "IN": return C["ADP"]
    if tag == "CC": return C["CONJ"]
    if tag == "CD": return C["NUM"]
    if tag in ("TO", "RP"): return C["PRT"]
    if tag in ("VB", "VBP"): return C["VB"]
    return {"VBZ": C["VBZ"], "VBD": C["VBD"], "VBN": C["VBN"], "VBG": C["VBG"], "MD": C["MD"], "WDT": C["WH"], "WP": C["WH"], "WRB": C["WH"], "WP$": C["WH"], ",": C["COMMA"]}.get(tag, C["OTHER"])


gold = [s for s in list(treebank.tagged_sents()) + list(conll2000.tagged_sents())]
random.Random(3).shuffle(gold)
dev, train = gold[:600], gold[600:]
tagger = PerceptronTagger(load=False)
tagger.train([[(w, t) for w, t in s if t != "-NONE-"] for s in train], nr_iter=5)
ok = tot = 0
for s in dev:
    s = [(w, t) for w, t in s if t != "-NONE-"]
    for (w, g), (_, p) in zip(s, tagger.tag([w for w, _ in s])): ok += ptb(g) == ptb(p); tot += 1
print(f"tagger accuracy on held-out gold (12-class mapping): {ok/tot:.3f}", flush=True)

lex = collections.defaultdict(collections.Counter)
trans = np.zeros((N, N, N), dtype=np.float64)
bi = np.zeros((N, N), dtype=np.float64)


def feed(cls):
    seq = [C["BOS"], C["BOS"]] + cls
    for a, b, c in zip(seq, seq[1:], seq[2:]):
        trans[a, b, c] += 1; bi[b, c] += 1


# gold sentences also teach the transitions and the lexicon
for s in gold:
    cls = []
    for w, t in s:
        if t == "-NONE-": continue
        c = rule_class(w.lower()) or ptb(t); cls.append(c); lex[w.lower()][c] += 1
    feed(cls)

n_tag = 0
for name, keep in (("tatoeba.txt", 120), ("oasst.txt", 150), ("web.txt", 30), ("smoltalk.txt", 1000)):
    with open(os.path.join(data, name), encoding="utf-8") as f:
        for line in f:
            if zlib.crc32(line.encode()) % 1000 >= keep: continue
            sent = []; kinds = []

            def flush():
                global n_tag
                if not sent: return
                words = [SURROGATE.get(rc, w) if rc is not None and rc in SURROGATE else w for w, rc in sent]
                tags = tagger.tag(words)
                cls = []
                for (w, rc), (_, t) in zip(sent, tags):
                    c = rc if rc is not None else ptb(t)
                    if c == C["OTHER"] and w == "<num>": c = C["NUM"]
                    cls.append(c); lex[w.lower()][c] += 1
                n_tag += len(cls); feed(cls); sent.clear()

            for kind, s in tok.tokens(line):
                if kind == "w":
                    lw = s.lower(); sent.append((s, rule_class(lw) if (lw in BE or lw in HAVE or lw in MD or lw in PRON or lw == "to") else None))
                elif kind == "n": sent.append(("1", C["NUM"]))
                elif kind == "c": pass
                else: flush()
            flush()
print("tagged tokens", n_tag, flush=True)

# transition probabilities, trigram backed off to bigram
bip = (bi + 0.5) / (bi + 0.5).sum(1, keepdims=True)
tri = np.zeros_like(trans)
for a in range(N):
    for b in range(N):
        tot = trans[a, b].sum(); tri[a, b] = (trans[a, b] + 8 * bip[b]) / (tot + 8)
words = [l.split("\t") for l in open(os.path.join(prep, "vocab.tsv"), encoding="utf-8").read().splitlines()]
V = len(words)
rows = np.zeros((V, 3, 2), dtype=np.uint8)
for i, (_, w, disp, _) in enumerate(words):
    i = int(i)
    if w == "<s>": dist = {C["BOS"]: 1.0}
    elif w == ",": dist = {C["COMMA"]: 1.0}
    elif w == "<num>": dist = {C["NUM"]: 1.0}
    elif rule_class(w) is not None: dist = {rule_class(w): 1.0}
    elif w in lex:
        tot = sum(lex[w].values()); dist = {c: n / tot for c, n in lex[w].items()}
    else: dist = {C["OTHER"]: 1.0}
    for k, (c, p) in enumerate(sorted(dist.items(), key=lambda kv: -kv[1])[:3]): rows[i, k] = (c, min(255, round(p * 255)))
with open(out, "wb") as f:
    f.write(b"IBPS" + struct.pack("<3I", 1, V, N) + rows.tobytes() + np.clip(np.round(tri * 65535), 1, 65535).astype("<u2").tobytes())
print("wrote", out, os.path.getsize(out), "bytes")
for w in ("doing", "going", "happy", "the", "run", "ran", "quickly", "of", "she", "what"):
    r = [x for x in words if x[1] == w]
    if r: print(w, [(CLS[c], p) for c, p in rows[int(r[0][0])] if p])
