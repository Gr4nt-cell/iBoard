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

## Prediction and AI

**On every phone (offline, built in):**

- **Word prediction** for 6 languages: prefix completions, typo correction, contractions and accents, word-pair and three-word context models trained on Tatoeba, and personal learning of your own words and phrases.
- **Touch-aware correction:** each letter is scored by how far the finger landed from every key, not just "neighbouring key or not". It also handles fuzzy completion of long words. In a simulated sloppy-typing test on the English dictionary (`SpatialCorrectionTest`), the first suggestion was right for 85.9% of mistyped words, against 64.4% with neighbour keys only.
- **Dictation** with OpenAI Whisper via whisper.cpp. The model is downloaded once from Hugging Face (Fast / Balanced / Accurate) in Settings › Offline Voice Model, then works offline.

**Where the phone has Gemini Nano** (Android AICore, a supported phone with a locked bootloader):

- Gemini Nano predictions are merged into the suggestion bar.
- Dictation uses Gemini Nano speech recognition.
- The ✨ key offers Translate, Fix grammar, Friendly, Professional, Shorter, Longer, Rephrase, Emojify and Summarize.

Gemini Nano is never bundled: AICore installs and runs it.

## Settings

Settings shares the keyboard background hues, translucent glass cards, rim highlights and accent controls. Light/dark follows the keyboard appearance preference. Tablets center the settings list at 720 dp. Gemini Nano status, its download, word prediction, dictation and microphone access sit together under Gemini Nano. All existing controls remain available.

## Emoji

`assets/fonts/aura-emoji.ttf` (**AURA Emoji**) is iBoard's own colour emoji font. Every emoji is generated for iBoard with Figma AI in the AURA style; the set is growing in batches of 100 (currently 248: smileys, hearts, hands and body parts, people, cat and monkey faces, popular animals and food). Emoji not yet in the font are hidden from the picker. Font importing and style selection have been removed.

The bundled font controls emoji rendering inside iBoard. The keyboard commits ordinary Unicode emoji; receiving apps use their own emoji rendering. Font provenance and the embedded license are recorded in `assets/fonts/FONT-NOTICE.md`.

## Privacy

Text and voice are processed on the phone (built-in engine, Whisper, or Gemini Nano); iBoard sends nothing you type or say to any server. The internet permission is used only to download the Whisper voice model and the optional sticker and GIF packs. Microphone access is optional and used only for dictation you start. Password fields turn suggestions and AI tools off. Learned words, clipboard history and settings stay on the phone, and Android app backup is disabled.

## Build and checks

Use JDK 17+, Gradle 8.13 and the Android SDK (compileSdk 36, NDK 28.2.13676358, CMake 3.22.1):

```
python3 tools/prepare_speech.py
gradle :app:testReleaseUnitTest :app:assembleDebug
```

The existing GitHub release signing configuration is preserved. `GITHUB_RUN_NUMBER` controls the version code, as before.

- `scripts/prepare-emoji-font.py`: checks the attached font's sequence coverage with FontTools and HarfBuzz; extracts a small set of glyph images for design references.
- `design/README.md`: Figma import instructions, design previews and validation details.

## Number pad and floating mode

- The number pad has direct @ and # keys. Long-press them for / and * respectively (with additional symbols available).
- Enable Settings > Keyboard Position > Floating Keyboard. Drag the Move header; position is saved. Tap Dock to restore the bottom keyboard. Floating mode uses the input-method window, with touches restricted to the keyboard card, and needs no overlay permission.

Password fields now suppress entrance animation, character previews and automatic shift refresh. Restarts of the same editor preserve the selected page and avoid rebuilding the key geometry. Password characters/spaces are committed without punctuation cleanup or the double-space period shortcut.
