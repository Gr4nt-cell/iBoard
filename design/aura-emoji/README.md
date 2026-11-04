# AURA Emoji build

Every glyph is generated with Figma AI in the AURA style, one emoji per image.

1. `figma-source/<codepoints>.jpg`: the generated images (256 px previews), named by Unicode code points.
2. `cut.py` removes the plain background (white, or a contrasting colour for white subjects).
3. `build_figma_only.sh` places each emoji on a 170×160 canvas, palettises it with `squant.js`
   (libimagequant via sharp), and builds the CBDT font with Noto Emoji's `add_glyphs.py` /
   `emoji_builder.py` and `aura_fo.tmpl.ttx.tmpl`. It also writes `supported_emoji.txt`.

New batches are added by dropping more images into `figma-source/` and re-running the build.
