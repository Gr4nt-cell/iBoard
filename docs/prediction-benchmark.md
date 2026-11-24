# Prediction benchmark (held-out sentences)

Words scored: 2946 in-vocabulary (18 of 2964 skipped as outside the vocabulary).

| Mode | Next-word top-1 | Next-word top-3 | Keystrokes saved | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|
| Instant layers (n-gram + habits + grammar) | 23.8% | 41.3% | 35.7% | 0.0 | 0.1 | 0.1 |
| Built-in engine (adds neural model) | 28.1% | 45.4% | 62.8% | 2.2 | 5.8 | 6.8 |
| Built-in + simulated Gemini Nano | 33.8% | 49.6% | 65.3% | 0.9 | 2.2 | 3.2 |

Parity: built-in alone reaches 91.6% of the top-3 accuracy of built-in + simulated Nano (target at least 90%).
Simulated Nano recall: 55% (assumed, not measured). Latency is per query on the JVM, not on a phone.
