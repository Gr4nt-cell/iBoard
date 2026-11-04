# AURA Emoji build

1. `figma-source/<codepoints>.jpg`: AURA-style emoji generated one per image with Figma AI (256 px previews); `cut.py` removes the white background.
2. Microsoft Fluent Emoji 3D (MIT) PNGs, including every skin tone, go through `grade.py` (`aura_grade`) to match the AURA look: S-curve contrast, richer saturation, darker mouths, edge depth, specular highlight and soft shadow.
3. Glyphs neither source covers (flags, mixed-tone groups) come from Twemoji (CC BY 4.0), shaded by `restyle_lumo.js` / `render_lumo.js`.
4. `build_main.sh` layers them (Figma > Fluent > Twemoji), palettises with `squant.js`, and builds the CBDT font with Noto Emoji's `add_glyphs.py` / `emoji_builder.py` and `aura.tmpl.ttx.tmpl`. `patch_figma.sh` swaps in new Figma batches.
