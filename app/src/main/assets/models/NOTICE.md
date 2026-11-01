# Bundled speech recognition

OpenAI Whisper Base multilingual, converted/quantized for whisper.cpp (Q5_1).
Source: https://huggingface.co/ggerganov/whisper.cpp
Revision: 5359861c739e955e79d9a303bcbc70fb988958b1
File: ggml-base-q5_1.bin (59,707,625 bytes)
SHA-256: 422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898
MIT license: WHISPER-LICENSE.txt; runtime license: ../models-whisper-LICENSE.txt
Runtime: whisper.cpp v1.7.6, a8d002cfd879315632a579e73f0148d06959de36.

Both model and native inference ship in the APK/AAB. On first use the model is
verified and copied from the APK to app-private no-backup storage. No network
permission, API key, system/cloud recognizer or model download is used.
16 kHz mono PCM is processed in memory. Audio is neither written to disk nor kept.
Microphone permission and a deliberate microphone tap are required. Tapping the
mic again finishes; tapping it during finishing cancels remaining results. Closing
the keyboard, changing input fields or typing a key cancels dictation.

Base balances multilingual accuracy, memory and package size. Accuracy and speed
vary by device, language, speaker and noise; phone/tablet latency is not yet measured.

Version 1.0.26: optimized native inference, greedy decoding, eight-second maximum
audio chunks, elapsed progress display, native cancellation and 45-second deadline.
