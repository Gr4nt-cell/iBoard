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

## AI: Gemini Nano only

Every AI feature in iBoard runs on **Gemini Nano**, Google's on-device model, through the ML Kit GenAI APIs (`genai-prompt`, `genai-speech-recognition`):

- **Word prediction:** completions, spelling corrections and next words in the suggestion bar.
- **Dictation:** on-device speech recognition; words stream in while you speak.
- **✨ AI tools** (the sparkle key): Translate, Fix grammar, Friendly, Professional, Shorter, Longer, Rephrase, Emojify and Summarize. A tool works on the selected text, or on the whole field when nothing is selected.

Gemini Nano is **not bundled in the APK**, and it cannot be: Android's **AICore** system service installs it, updates it and runs it. iBoard asks AICore for it in Settings › Gemini Nano. It works only on phones that support Gemini Nano (for example Pixel 9 and later, Galaxy S25 and later, and recent Xiaomi, OPPO, vivo and Honor flagships) with a **locked bootloader**. On other phones iBoard types normally without prediction, dictation or AI tools.

The earlier dictionary prediction, Whisper dictation, online dictation and ML Kit Translate have been removed.

## Settings

Settings shares the keyboard background hues, translucent glass cards, rim highlights and accent controls. Light/dark follows the keyboard appearance preference. Tablets center the settings list at 720 dp. Gemini Nano status, its download, word prediction, dictation and microphone access sit together under Gemini Nano. All existing controls remain available.

## Emoji

`assets/fonts/aura-emoji.ttf` (**AURA Emoji**) is iBoard's own colour emoji font. Every emoji is generated for iBoard with Figma AI in the AURA style; the set is growing in batches of 100 (currently 248: smileys, hearts, hands and body parts, people, cat and monkey faces, popular animals and food). Emoji not yet in the font are hidden from the picker. Font importing and style selection have been removed.

The bundled font controls emoji rendering inside iBoard. The keyboard commits ordinary Unicode emoji; receiving apps use their own emoji rendering. Font provenance and the embedded license are recorded in `assets/fonts/FONT-NOTICE.md`.

## Privacy

Text and voice are processed by Gemini Nano on the phone; iBoard sends nothing you type or say to any server. The internet permission is used only for the optional sticker and GIF packs. Microphone access is optional and used only for dictation you start. Password fields turn suggestions and AI tools off. Clipboard history and settings stay on the phone, and Android app backup is disabled.

## Build and checks

Use JDK 17+, Gradle 8.13 and the Android SDK (compileSdk 36). No NDK is needed any more:

```
gradle :app:assembleDebug
```

The existing GitHub release signing configuration is preserved. `GITHUB_RUN_NUMBER` controls the version code, as before.

- `scripts/prepare-emoji-font.py`: checks the attached font's sequence coverage with FontTools and HarfBuzz; extracts a small set of glyph images for design references.
- `design/README.md`: Figma import instructions, design previews and validation details.

## Number pad and floating mode

- The number pad has direct @ and # keys. Long-press them for / and * respectively (with additional symbols available).
- Enable Settings > Keyboard Position > Floating Keyboard. Drag the Move header; position is saved. Tap Dock to restore the bottom keyboard. Floating mode uses the input-method window, with touches restricted to the keyboard card, and needs no overlay permission.

Password fields now suppress entrance animation, character previews and automatic shift refresh. Restarts of the same editor preserve the selected page and avoid rebuilding the key geometry. Password characters/spaces are committed without punctuation cleanup or the double-space period shortcut.
