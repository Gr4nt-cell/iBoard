#!/usr/bin/env python3
"""Train the small bundled next-word language model and export it as an int8 file.

Model: tied-embedding GRU language model (E-dim embeddings, L GRU layers of H units, projection
back to E, output = projection . embedding^T + bias). Stateful truncated BPTT over contiguous
streams, time-budgeted cosine learning-rate schedule (finishes in --minutes whatever the speed).

Usage: train_lm.py PREP_DIR OUT_FILE [--emb 192 --hidden 512 --layers 1 --batch 64 --seq 32
                                       --minutes 45 --lr 3e-3 --bench]
Export format (little endian) is read by app/.../engine/BundledLm.kt.
"""
import argparse, math, os, struct, sys, time
import numpy as np, torch, torch.nn as nn, torch.nn.functional as F

ap = argparse.ArgumentParser()
ap.add_argument("prep"); ap.add_argument("out")
ap.add_argument("--emb", type=int, default=192); ap.add_argument("--hidden", type=int, default=512)
ap.add_argument("--layers", type=int, default=1); ap.add_argument("--batch", type=int, default=64)
ap.add_argument("--seq", type=int, default=32); ap.add_argument("--minutes", type=float, default=45)
ap.add_argument("--lr", type=float, default=3e-3); ap.add_argument("--dropout", type=float, default=0.15)
ap.add_argument("--samples", type=int, default=3000); ap.add_argument("--full-minutes", type=float, default=4)
ap.add_argument("--bench", action="store_true"); ap.add_argument("--vocab", type=int, default=0)
a = ap.parse_args()
torch.set_num_threads(2); torch.manual_seed(1)
NSPECIAL = 5  # <pad> <unk> <s> <num> ,


def load(name): return torch.from_numpy(np.fromfile(os.path.join(a.prep, name), dtype=np.uint16).astype(np.int64))


class LM(nn.Module):
    def __init__(s, V, E, H, L, p):
        super().__init__()
        s.emb = nn.Embedding(V, E); s.gru = nn.GRU(E, H, L, batch_first=True)
        s.proj = nn.Linear(H, E); s.bias = nn.Parameter(torch.zeros(V)); s.drop = nn.Dropout(p)
        nn.init.normal_(s.emb.weight, 0, 0.08)

    def hidden_out(s, x, h=None):
        o, h = s.gru(s.drop(s.emb(x)), h)
        return s.proj(s.drop(o)), h

    def forward(s, x, h=None):
        z, h = s.hidden_out(x, h)
        return F.linear(z, s.emb.weight, s.bias), h

    def sampled_loss(s, x, y, h, S, logq):
        """Sampled softmax (log-uniform candidates shared by the batch, log-Q corrected)."""
        z, h = s.hidden_out(x, h); z = z.reshape(-1, z.size(-1)); y = y.reshape(-1)
        cand = torch.multinomial(s.qprob, S, replacement=True)
        w, b = s.emb.weight, s.bias
        true = (z * w[y]).sum(-1) + b[y] - logq[y]
        samp = z @ w[cand].T + b[cand] - logq[cand]
        samp = samp.masked_fill(cand[None, :] == y[:, None], -1e9)
        logits = torch.cat([true[:, None], samp], 1)
        return F.cross_entropy(logits, torch.zeros(len(y), dtype=torch.long)), h


def batchify(d, B):
    n = d.size(0) // B
    return d[: n * B].view(B, n)


@torch.no_grad()
def evaluate(model, data, B=64, T=a.seq, limit=400_000):
    model.eval(); d = batchify(data[:limit], B); tot = 0.0; n = 0; hit1 = hit3 = hit5 = m = 0; h = None
    for i in range(0, d.size(1) - 1 - T, T):
        x, y = d[:, i:i + T], d[:, i + 1:i + T + 1]
        lo, h = model(x, h)
        tot += F.cross_entropy(lo.reshape(-1, lo.size(-1)), y.reshape(-1), reduction="sum").item(); n += y.numel()
        lo2 = lo.clone(); lo2[..., :NSPECIAL] = -1e9
        top = lo2.topk(5, dim=-1).indices; mask = y >= NSPECIAL
        t = y.unsqueeze(-1)
        hit1 += ((top[..., :1] == t).any(-1) & mask).sum().item(); hit3 += ((top[..., :3] == t).any(-1) & mask).sum().item()
        hit5 += ((top == t).any(-1) & mask).sum().item(); m += mask.sum().item()
    model.train()
    return math.exp(tot / n), hit1 / m, hit3 / m, hit5 / m


def q8(w):
    """Per-row symmetric int8 quantisation: returns (int8 array, float32 scales)."""
    w = w.detach().cpu().float().numpy()
    s = np.maximum(np.abs(w).max(axis=1), 1e-8) / 127.0
    return np.clip(np.round(w / s[:, None]), -127, 127).astype(np.int8), s.astype(np.float32)


def export(model, path, V, E, H, L):
    parts = [b"IBLM", struct.pack("<5I", 1, V, E, H, L)]
    def put(arr): parts.append(np.ascontiguousarray(arr).tobytes())
    q, s = q8(model.emb.weight); put(q); put(s)
    put(model.bias.detach().float().numpy())
    for l in range(L):
        for nm in (f"weight_ih_l{l}", f"weight_hh_l{l}"):
            q, s = q8(getattr(model.gru, nm)); put(q); put(s)
        put(getattr(model.gru, f"bias_ih_l{l}").detach().float().numpy()); put(getattr(model.gru, f"bias_hh_l{l}").detach().float().numpy())
    q, s = q8(model.proj.weight); put(q); put(s); put(model.proj.bias.detach().float().numpy())
    with open(path, "wb") as f: f.write(b"".join(parts))


def dequantize_into(model):
    """Round-trip every matrix through int8 so evaluation shows the exported model's accuracy."""
    with torch.no_grad():
        for p in [model.emb.weight, model.proj.weight] + [getattr(model.gru, f"weight_{k}_l{l}") for l in range(a.layers) for k in ("ih", "hh")]:
            q, s = q8(p); p.copy_(torch.from_numpy(q.astype(np.float32) * s[:, None]))


train = load("train.u16"); valid = load("valid.u16"); test = load("test.u16")
V = a.vocab or int(max(train.max().item(), valid.max().item(), test.max().item()) + 1)
model = LM(V, a.emb, a.hidden, a.layers, a.dropout)
_k = torch.arange(V, dtype=torch.float64)
model.qprob = ((torch.log(_k + 2) - torch.log(_k + 1)) / math.log(V + 1)).float()
LOGQ = torch.log(a.samples * model.qprob)
print(f"params {sum(p.numel() for p in model.parameters())/1e6:.2f}M  vocab {V}  train tokens {train.numel():,}", flush=True)
opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=0.01)
d = batchify(train, a.batch); T = a.seq
budget = a.minutes * 60; t0 = time.time(); step = 0; h = None; seen = 0; run = 0.0; best = 1e9
done = False; epoch = 0
while not done:
    off = int(torch.randint(0, T, (1,)).item())
    for i in range(off, d.size(1) - 1 - T, T):
        el = time.time() - t0
        if el > budget or (a.bench and step >= 20): done = True; break
        frac = el / budget
        lr = a.lr * min(1.0, (step + 1) / 200) * (0.03 + 0.97 * 0.5 * (1 + math.cos(math.pi * frac)))
        if el >= budget - a.full_minutes * 60: lr = min(lr, a.lr * 0.05)
        for g in opt.param_groups: g["lr"] = lr
        x, y = d[:, i:i + T], d[:, i + 1:i + T + 1]
        if h is not None and torch.rand(1).item() < 0.1: h = None
        if el < budget - a.full_minutes * 60:
            loss, h = model.sampled_loss(x, y, h, a.samples, LOGQ)
        else:  # last minutes: exact softmax so the output probabilities are calibrated
            lo, h = model(x, h); loss = F.cross_entropy(lo.reshape(-1, V), y.reshape(-1))
        h = h.detach()
        opt.zero_grad(set_to_none=True); loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0); opt.step()
        step += 1; seen += y.numel(); run = 0.98 * run + 0.02 * loss.item() if step > 1 else loss.item()
        if step % 200 == 0:
            print(f"ep {epoch} step {step} tok {seen/1e6:.1f}M loss {run:.3f} ppl {math.exp(run):.1f} lr {lr:.5f} {seen/(time.time()-t0)/1000:.1f}k tok/s  {el/60:.1f}min", flush=True)
        if step % 4000 == 0:
            p, t1, t3, t5 = evaluate(model, valid); print(f"  VALID ppl {p:.1f}  top1 {t1:.3f} top3 {t3:.3f} top5 {t5:.3f}", flush=True)
    epoch += 1
if a.bench:
    print(f"bench: {seen/(time.time()-t0):.0f} tok/s"); sys.exit()
p, t1, t3, t5 = evaluate(model, test, limit=2_000_000); print(f"TEST float  ppl {p:.1f} top1 {t1:.3f} top3 {t3:.3f} top5 {t5:.3f}", flush=True)
export(model, a.out, V, a.emb, a.hidden, a.layers)
dequantize_into(model)
p, t1, t3, t5 = evaluate(model, test, limit=2_000_000); print(f"TEST int8   ppl {p:.1f} top1 {t1:.3f} top3 {t3:.3f} top5 {t5:.3f}", flush=True)
print("exported", a.out, os.path.getsize(a.out) / 1e6, "MB", flush=True)
torch.save(model.state_dict(), a.out + ".pt")
