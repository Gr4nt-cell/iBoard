# iBoard

An offline Android keyboard with Liquid Glass keys, local word prediction and the supplied iOS-style color emoji font built into the app.

## Keyboard

- Light and dark glass themes with soft pink, lavender and blue gradients, highlighted rims, pressed-key feedback and key previews.
- Android 12+ system backdrop blur where the device enables cross-window blur. Blur is bounded to the keyboard view; unsupported devices use a readable frosted gradient. No screen capture permission is used.
- Phones and tablets: centered portrait layouts, split landscape keys with an inactive middle gutter, and a space bar for each thumb. Landscape keeps the host app visible instead of opening the fullscreen extract editor.
- `123` opens a large number pad with digits, operators, backspace, return, `ABC` and access to symbols. Numeric fields open the pad directly.
- The bottom utility icons share one rounded rectangular glass capsule. Split landscape has one matching capsule per half, with the globe and mic together.
- Number row, accents, cursor movement while holding space, long-press backspace, clipboard history and language switching remain available.
- Emoji and clipboard panels resize with the device; the emoji grid adapts from 6 to 20 columns.

## Prediction, entirely on the phone

The six bundled frequency dictionaries support English, Español, Français, Português, Deutsch and العربية. Local prediction includes prefix completions, weighted neighboring-key corrections, transpositions, missing leading letters, accent/contraction matching, startup word pairs, learned words, bigrams and trigrams. Valid words and names are protected; autocorrection can be undone with backspace. Password fields disable suggestions and incognito fields do not train the user dictionary.

These changes improve the existing engine; they are not a claim of parity with Yandex or another commercial keyboard. Corpus-trained bigram/trigram models are bundled for all six languages, with hundreds of thousands of contexts (see per-language JSON for exact counts). They interpolate the last two words, rank completions and combine with local personal learning. This is statistical machine learning, not a generative LLM.

## Settings

Settings shares the keyboard background hues, translucent glass cards, rim highlights and accent controls. Light/dark follows the keyboard appearance preference. Tablets center the settings list at 720 dp. Offline dictation, microphone access and model information sit together under Offline intelligence. All existing controls remain available.

## Emoji

`assets/fonts/ios-style.ttf` is the user-supplied `NotoColorEmoji.ttf`, identifying itself as Apple MFFM Color Emoji. Its bytes are unchanged. Coverage checks found all 3,564 tested emoji sequences supported, including skin tones and flags. The old Fluent 3D artwork and its generator have been removed. No emoji download is needed. An optional local font importer remains available.

The font controls rendering inside iBoard. The keyboard commits ordinary Unicode emoji; receiving apps use their own emoji rendering. Font provenance and embedded notices are recorded in `assets/fonts/FONT-NOTICE.md`.

## Offline privacy

There is no internet permission, cloud API, API-key screen, translation/grammar assistant or generative LLM dependency. Microphone permission is optional and requested after a deliberate microphone action. Whisper Base Q5_1 and its native runtime are bundled. Audio is processed in memory and is neither saved nor transmitted. No model download or separate speech app is needed. Tap the mic beside the globe to start, tap again to finish, or tap during finishing to cancel. Typing a key, closing the keyboard or changing fields cancels dictation and discards late results. Upgrade migration removes old provider preferences and credentials, cancels old model downloads and removes obsolete model files. Learned words, clipboard history and settings remain local, with Android app backup disabled.

## Build and checks

Use JDK 17, Gradle 8.13 and the Android SDK (compileSdk 36, NDK 28.2.13676358, CMake 3.22.1):

```
python3 tools/prepare_speech.py
gradle :app:assembleDebug
```

The existing GitHub release signing configuration is preserved. `GITHUB_RUN_NUMBER` controls the version code, as before.

- `scripts/check-offline-keyboard.py`: production predictor, persistence and geometry tests using JVM context stubs. Set `IBOARD_GRADLE_LIB` to Gradle 8.13's `lib` directory when it is not at the task workspace location.
- `scripts/prepare-emoji-font.py`: checks the attached font's sequence coverage with FontTools and HarfBuzz; extracts a small set of glyph images for design references.
- `design/README.md`: Figma import instructions, design previews and validation details.

## Speech finalizing fix (1.0.26)

The previous debug APK optimized only the JNI shim; the transformer and matrix
kernels were unoptimized. All native engine targets now use -O3 in every build
variant. Dictation uses one greedy decoder instead of a five-hypothesis beam,
processes chunks up to eight seconds, and reports elapsed transcription time.
Native cancellation and a 45-second decoding deadline prevent an indefinite
finishing state. Capture uses nonblocking reads, sample-based timing and
thread-owned stop/release; short final utterances are drained before completion.

Regression checks: `scripts/check-speech-jni.py` exercises the production Kotlin
and JNI bridge with the actual bundled model, including transcription, native
deadline, concurrent cancellation/close and repeat close.
`scripts/check-speech-capture.py` verifies the production capture lifecycle against
deterministic Android audio stubs. These host tests do not replace device testing.

Version 1.0.27 centers a compact utility capsule around its icons instead of stretching it across the screen. Five controls use 44dp touch targets and 8dp gaps (252dp total width). Landscape clusters remain compact inside each keyboard half; the existing speech fixes remain included.
