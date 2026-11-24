#!/usr/bin/env python3
"""Fetch the licence-clean English training text for the iBoard prediction engine.

Sources (all redistributable / permissive, see app/src/main/assets/prediction/NOTICE.md):
  * Tatoeba English sentences            CC BY 2.0 FR   everyday short sentences
  * Anthropic/hh-rlhf (human+assistant)  MIT            casual chat turns
  * OpenAssistant/oasst1 (en)            Apache-2.0     human-written messages
  * HuggingFaceTB/smoltalk (everyday)    Apache-2.0     everyday conversations
  * HuggingFaceFW/fineweb-edu sample     ODC-By         clean grammatical web prose

Output: one text file per source in OUT, one paragraph/utterance per line.
Usage: fetch_corpus.py OUT_DIR [--web-docs N]
"""
import bz2, gzip, io, json, os, sys, urllib.request

out = sys.argv[1]
web_docs = 30000
if "--web-docs" in sys.argv:
    web_docs = int(sys.argv[sys.argv.index("--web-docs") + 1])
os.makedirs(out, exist_ok=True)


def save(name, lines):
    n = 0
    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
        for line in lines:
            line = " ".join(line.split())
            if len(line) >= 2:
                f.write(line + "\n"); n += 1
    print(name, n, "lines", flush=True)


def tatoeba():
    url = "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2"
    raw = bz2.decompress(urllib.request.urlopen(url, timeout=300).read()).decode("utf-8")
    for row in raw.splitlines():
        p = row.split("\t")
        if len(p) == 3: yield p[2]


def hf_file(repo, path):
    url = f"https://huggingface.co/datasets/{repo}/resolve/main/{path}"
    return urllib.request.urlopen(url, timeout=300).read()


def hh():
    for sub in ("helpful-base", "helpful-online", "helpful-rejection-sampled", "harmless-base"):
        data = gzip.decompress(hf_file("Anthropic/hh-rlhf", f"{sub}/train.jsonl.gz")).decode("utf-8")
        for row in data.splitlines():
            t = json.loads(row)["chosen"]
            for turn in t.replace("\n\nAssistant:", "\n\nHuman:").split("\n\nHuman:"):
                yield turn


def oasst():
    data = gzip.decompress(hf_file("OpenAssistant/oasst1", "2023-04-12_oasst_ready.trees.jsonl.gz")).decode("utf-8")
    def walk(node):
        if node.get("lang") == "en": yield node["text"]
        for r in node.get("replies", []): yield from walk(r)
    for row in data.splitlines():
        yield from walk(json.loads(row)["prompt"])


def smoltalk():
    from datasets import load_dataset
    ds = load_dataset("HuggingFaceTB/smoltalk", "everyday-conversations", split="train")
    for r in ds:
        for m in r["messages"]: yield m["content"]


def web():
    from datasets import load_dataset
    ds = load_dataset("HuggingFaceFW/fineweb-edu", name="sample-10BT", split="train", streaming=True)
    for i, r in enumerate(ds):
        if i >= web_docs: break
        yield r["text"].replace("\n", " ")


for name, fn in (("tatoeba.txt", tatoeba), ("hh.txt", hh), ("oasst.txt", oasst), ("smoltalk.txt", smoltalk), ("web.txt", web)):
    try:
        save(name, fn())
    except Exception as e:
        print("FAILED", name, repr(e), flush=True)
