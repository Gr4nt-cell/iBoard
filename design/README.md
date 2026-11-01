# iBoard / Liquid Glass design

Ten editable references cover the seven existing keyboard layouts plus Settings
in light, dark and tablet modes. Key bounds are exported from production layout
code. Settings uses translucent rounded cards, the keyboard hues and accent,
centered tablet width, and an Offline intelligence section. These are design
references, not screenshots of a running Android keyboard.

Figma file: https://www.figma.com/design/HBxlRryQHVGvA0ArU6kEmQ

Figma canvas editing is blocked by the account's Collab-seat call limit. The
canvas has not been updated. Run the Figma Scripter plugin in the file and paste
`figma-liquid-glass.js` to import editable layers. A repeated run creates a new
page. The script contains ten screens, 26 shared components, eight color
variables with Light/Dark modes, Roboto labels and vector icons. The 40 small
emoji preview images are individual supplied-font glyphs, not flattened UI.
The app renders the original supplied font directly.

Regenerate with `python3 scripts/check-offline-keyboard.py`, then
`node design/build-reference.cjs`. `node design/check-import.cjs` checks the
import using a local scene-graph mock; this does not verify a real Figma import.
The visual reference and Settings preview were inspected locally.

Validation: signed debug APK 1.0.27 built with native speech runtimes for
arm64-v8a, armeabi-v7a and x86_64. Manifest inspection confirms microphone and
vibration permissions, with no internet permission. All six prediction models
and the exact speech-model checksum are verified inside the APK; the attached
emoji font is unchanged. The bundled speech model transcribed whisper.cpp's JFK
sample offline on the build host. This is a smoke test, not an accuracy/latency
benchmark on Android. 37,738 Host/JVM checks cover predictions, learned-word
persistence and responsive geometry. Per-language JSON records corpus-held-out
prediction metrics; they are not comparisons to Yandex or other keyboards.

Physical phone/tablet testing remains necessary for microphone permission flows,
background/field-switch cancellation, multilingual audio, noisy speech,
latency, keyboard rotation, blur, touch and accessibility. Android blur depends
on device support and settings. Recording audio is never written to disk.

GitHub rejected branch creation with `403 Resource not accessible by integration`.
The implementation is committed locally on `gpt`; it has not been published.

The utility row is now one rounded rectangular capsule in portrait, with matching
split capsules in landscape. Globe and mic stay together in the same landscape
capsule. `capsule-preview.png` shows light and dark treatments. Capsule bounds are
exported from production geometry. Speech v26 additionally passes 71 capture
lifecycle checks and production JNI transcription, timeout and cancellation tests.
Native Android compile commands were checked for -O3 on all inference kernels.

Version 1.0.27 centers a compact utility capsule around its icons instead of stretching it across the screen. Five controls use 44dp touch targets and 8dp gaps (252dp total width). Landscape clusters remain compact inside each keyboard half; the existing speech fixes remain included.
