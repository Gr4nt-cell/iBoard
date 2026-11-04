# Gemoji

iBoard's bundled 3D color emoji font (`app/src/main/assets/fonts/gemoji.ttf`, CBDT, works on Android 8+).

Pipeline:
1. `originals.js` draws iBoard's own smileys/symbols → `override/*.svg`.
2. `restyle.js <twemoji/assets/svg> <out> override` adds 3D shading (per-shape light→dark gradients, gloss, bottom shade, contact shadow) to all 4,000+ Twemoji SVGs, replacing those with an iBoard original.
3. `render.js` rasterises to 136×128 PNG (Playwright/Chromium), `quant.py` palettises them.
4. Noto Emoji's `add_glyphs.py` + `emoji_builder.py -S -V` with `gemoji.tmpl.ttx.tmpl` build the CBDT font.

Artwork license: CC BY 4.0 (Twemoji by Twitter, Inc and contributors). See `app/src/main/assets/fonts/GEMOJI-LICENSE.txt`.
