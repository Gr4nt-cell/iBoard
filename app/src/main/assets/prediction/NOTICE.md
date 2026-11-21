# Embedded iBoard prediction models

Copyright Tatoeba contributors. Sentences: https://tatoeba.org
Source exports: https://tatoeba.org/en/downloads
License: Creative Commons Attribution 2.0 France
https://creativecommons.org/licenses/by/2.0/fr/

Changes: iBoard tokenizes and lowercases sentences, excludes held-out sentences,
counts word bigrams/trigrams, prunes rare contexts and ships the resulting counts.
No affiliation or endorsement is implied. The corpus is not a source of personal
keyboard data. The application only learns personal words locally when enabled.

Each language JSON records the exact source export URL, processed-prefix SHA-256,
packaged model SHA-256, sentence counts and held-out evaluation. Downloads change
weekly; packaged files are fixed and do not update themselves. Training script:
tools/train_prediction.py. Training is a build-time operation; inference is offline.

This is a statistical language model, combined with frequency dictionaries,
keyboard-aware correction and optional local learning. It is not a generative LLM.
No measured comparison to a commercial keyboard has been made.
