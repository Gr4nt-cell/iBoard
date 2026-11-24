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


## English neural model, n-gram tables and grammar table (en.lm, en.ng, en.pos, en.vocab.tsv)

Trained on text from these sources (tools/prediction/fetch_corpus.py):

- Tatoeba English sentences, Creative Commons Attribution 2.0 France, https://tatoeba.org
- Anthropic/hh-rlhf, MIT License
- OpenAssistant/oasst1 (English messages), Apache License 2.0
- HuggingFaceTB/smoltalk (everyday-conversations), Apache License 2.0
- HuggingFaceFW/fineweb-edu (sample-10BT, first 30,000 documents), ODC-By 1.0

Changes: the text is tokenised and lower-cased, a 32,000-word vocabulary and word statistics are derived,
and the resulting model weights (int8), n-gram probabilities and word-class table are shipped. No source
text is included in the app. The grammar table was derived with a tagger trained on the Penn Treebank sample
and CoNLL-2000 corpora distributed with NLTK. No personal keyboard data is used for training; the app only
learns from your typing on your phone, when enabled.
