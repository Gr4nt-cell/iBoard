# iBoard

An iOS-inspired keyboard for Android with soft gradient glass keys, AI word prediction, a number row, clipboard history and six languages.

## Get the app

Every push to `main` builds the APK on GitHub Actions and publishes it to the **`latest`** release:
**Releases → latest → `iBoard.apk`**. Install it, open **iBoard**, then tap **1. Enable** and **2. Switch to iBoard**.

Builds are signed with the same key every time, so new versions install over old ones.

## Features

- **iOS-style layout** — glass keys on a pink → lavender → blue gradient (four gradients to choose from), character preview bubbles, long-press accents, the "keys slide up" entrance animation from iOS 27.
- **Light and dark mode** — follows the system or can be forced either way.
- **Number row** on top (can be switched off).
- **Prediction**
  - On-device: 40,000-word frequency dictionary per language, keyboard-aware typo correction, accent fixing (`cafe` → `café`), and next-word prediction that learns from how you type (stored only on the phone).
  - AI (optional): context-aware next words, corrections that use the whole sentence (`I want to by` → `buy`), and phrase completion. Works with Anthropic Claude or any OpenAI-compatible API (OpenAI, Gemini, Groq, OpenRouter…). Never runs in password or incognito fields.
  - Autocorrect on space, tap ⌫ right after to undo it.
- **Clipboard** — history of the last 40 copies, pinning, a one-tap "paste" chip for something you just copied.
- **Emoji** — iOS-style emoji keyboard with Search Emoji, category bar, frequently used and remembered skin tones. Emoji in the keyboard are drawn with glossy 3D art from [Microsoft Fluent Emoji](https://github.com/microsoft/fluentui-emoji) (MIT); flags use the phone's font. Generated with `tools/gen_emoji3d.py`.
- **Languages** — English, Español, Français (AZERTY), Português, Deutsch (QWERTZ), العربية. Tap 🌐 to switch; long-press 🌐 for other keyboards.
- iOS touches: double-space for a period, auto-capitalization, hold space to move the cursor, hold ⌫ to delete words, return key labelled go / search / send.

## Building locally

```
gradle :app:assembleRelease
```
Requires JDK 17 and the Android SDK (compileSdk 35). The bundled word lists and emoji data are generated with `tools/gen_assets.py` from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) and [unicode-emoji-json](https://github.com/muan/unicode-emoji-json).
