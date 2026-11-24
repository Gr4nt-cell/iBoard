# Prediction benchmark (held-out sentences)

Words scored: 2946 in-vocabulary (18 of 2964 skipped as outside the vocabulary).

| Mode | Next-word top-1 | Next-word top-3 | Keystrokes saved | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|
| Instant layers (n-gram + habits + grammar) | 23.8% | 41.3% | 35.7% | 0.0 | 0.1 | 0.1 |
| Built-in engine (adds neural model) | 26.4% | 44.0% | 61.8% | 2.3 | 6.4 | 8.1 |
| Built-in + simulated Gemini Nano | 31.9% | 46.9% | 64.1% | 1.0 | 1.8 | 3.4 |

Parity: built-in alone reaches 93.7% of the top-3 accuracy of built-in + simulated Nano (target at least 90%).
Simulated Nano recall: 55% (assumed, not measured). Latency is per query on the JVM, not on a phone.
