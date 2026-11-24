# iBoard prediction pipeline

Builds the English language pack used by `app/src/main/java/com/gr4nt/iboard/predict/engine/`.
Everything runs on a CPU; the numbers below are what the shipped files were built with.

```
pip install torch numpy nltk datasets
python3 tools/prediction/fetch_corpus.py data --web-docs 30000      # licence-clean text, ~400 MB
python3 tools/prediction/prep.py data prep --vocab 32000            # tokenise, vocab.tsv, train/valid/test ids, bench_en.txt
python3 tools/prediction/train_lm.py prep en.lm --emb 192 --hidden 512 --layers 1 --batch 64 --minutes 55 --full-minutes 4
python3 tools/prediction/build_ngram.py prep en.ng                  # pruned Kneser-Ney 2/3/4-gram tables
NLTK_DATA=<dir with treebank + conll2000> python3 tools/prediction/build_pos.py data prep en.pos
cp prep/vocab.tsv app/src/main/assets/prediction/en.vocab.tsv
cp en.lm en.ng en.pos app/src/main/assets/prediction/
cp prep/bench_en.txt app/src/test/resources/prediction/
```

* `tok.py` is the tokenizer. `engine/Tokenizer.kt` implements the same rules; `PredictionEngineTest.tokenizerMatchesTraining` guards them.
* The LM is a tied-embedding GRU trained with sampled softmax (exact softmax for the last minutes) and exported with per-row int8 quantisation. `BundledLm.kt` reads that file directly; there is no TFLite/ONNX runtime in the app.
* Held-out test/valid streams are chosen by line hash, so no benchmark sentence is in the training data.
* Add another language by producing `<lang>.vocab.tsv`, `.lm`, `.ng`, `.pos` with the same scripts; `PredictionEngine.load` picks them up by name.
