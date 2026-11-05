# AURA Emoji build

Every glyph is generated with Figma AI in the AURA style, one emoji per image.

1. `figma-source/<codepoints>.jpg`: the generated images (256 px previews), named by Unicode code points.
2. `cut.py` removes the plain background (white, or a contrasting colour for white subjects).
3. `build_figma_only.sh` places each emoji on a 170×160 canvas, palettises it with `squant.js`
   (libimagequant via sharp), and builds the CBDT font with Noto Emoji's `add_glyphs.py` /
   `emoji_builder.py` and `aura_fo.tmpl.ttx.tmpl`. It also writes `supported_emoji.txt`.

New batches are added by dropping more images into `figma-source/` and re-running the build.

## Batches 4 and 5 (sheets)

Figma AI credits ran out, so later batches were generated as 4x4 sheets in `sheets/`:
OpenArt Nano Banana 2 (batch 4, using existing AURA emoji as a style reference) and Gamma
(batch 5). Sheets are cut into single emoji with `cut_th.py` (fixed grid, tight white threshold)
or `objcut.py` (finds each object and drops cast shadows). The cut PNGs are in `cut/`.
A third-party logo on one laptop was painted out before cutting.
