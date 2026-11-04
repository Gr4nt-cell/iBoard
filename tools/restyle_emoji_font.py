#!/usr/bin/env python3
"""Apply the AURA glossy treatment and rebrand a supplied CBDT color emoji font."""

from __future__ import annotations

import argparse
from io import BytesIO
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageChops, ImageEnhance, ImageFilter

OFL_TEXT = (
    "This Font Software is licensed under the SIL Open Font License, Version 1.1. "
    "This Font Software is distributed on an AS IS BASIS, WITHOUT WARRANTIES OR "
    "CONDITIONS OF ANY KIND, either express or implied. See the SIL Open Font License "
    "for the specific language, permissions and limitations governing your use of "
    "this Font Software."
)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def restyle_png(data: bytes) -> bytes:
    """Give existing color art stronger color, soft depth, and a restrained gloss."""
    source = Image.open(BytesIO(data)).convert("RGBA")
    alpha = source.getchannel("A")
    width, height = source.size

    color = ImageEnhance.Color(source.convert("RGB")).enhance(1.2)
    color = ImageEnhance.Contrast(color).enhance(1.07).convert("RGBA")
    color.putalpha(alpha)

    # A small offset shadow gives each illustration a raised, sticker-like edge.
    blur = max(0.6, min(width, height) * 0.022)
    offset = max(1, round(height * 0.018))
    shadow_alpha = ImageChops.offset(alpha, 0, offset).filter(ImageFilter.GaussianBlur(blur))
    shadow_alpha = shadow_alpha.point(lambda value: round(value * 0.34))
    shadow = Image.new("RGBA", source.size, (17, 18, 42, 0))
    shadow.putalpha(shadow_alpha)
    result = Image.alpha_composite(shadow, color)

    # A transparent white highlight fades across the top half of the glyph.
    gloss = Image.new("RGBA", source.size, (255, 255, 255, 0))
    gloss_gradient = Image.new("L", (1, height))
    gloss_gradient.putdata([
        round(48 * max(0.0, 1.0 - (y / max(1, height * 0.46))) ** 2)
        for y in range(height)
    ])
    gloss_alpha = ImageChops.multiply(alpha, gloss_gradient.resize(source.size))
    gloss.putalpha(gloss_alpha)
    result = Image.alpha_composite(result, gloss)

    # A subtle lower-edge shade adds volume without changing the transparent canvas.
    shade = Image.new("RGBA", source.size, (30, 20, 58, 0))
    shade_gradient = Image.new("L", (1, height))
    shade_gradient.putdata([
        round(24 * max(0.0, (y / max(1, height)) - 0.52) / 0.48)
        for y in range(height)
    ])
    shade_alpha = ImageChops.multiply(alpha, shade_gradient.resize(source.size))
    shade.putalpha(shade_alpha)
    result = Image.alpha_composite(result, shade)

    # The source is a color bitmap font; limiting each small glyph to a 64-color
    # optimized palette keeps the app download compact while retaining smooth art.
    result = result.quantize(colors=64, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).convert("RGBA")
    output = BytesIO()
    result.save(output, format="PNG", optimize=True, compress_level=9)
    return output.getvalue()


def set_name(font: TTFont, name_id: int, value: str) -> None:
    # Add Windows Unicode and Macintosh English records so Android and desktop
    # font inspectors see the same AURA family name.
    font["name"].setName(value, name_id, 3, 1, 0x409)
    font["name"].setName(value, name_id, 1, 0, 0)


def transform(source: Path, destination: Path) -> tuple[int, int]:
    font = TTFont(source)
    if "CBDT" not in font or "CBLC" not in font:
        raise ValueError("The supplied font must contain CBDT/CBLC color bitmap tables.")

    transformed = 0
    unchanged = 0
    for strike in font["CBDT"].strikeData:
        for glyph in strike.values():
            image_data = getattr(glyph, "imageData", b"")
            if image_data.startswith(PNG_SIGNATURE):
                glyph.imageData = restyle_png(image_data)
                transformed += 1
            else:
                unchanged += 1

    for name_id, value in (
        (1, "AURA Emoji"),
        (2, "Regular"),
        (3, "AURA Emoji 1.0"),
        (4, "AURA Emoji"),
        (6, "AURAEmoji"),
        (8, "Google, Inc."),
        (9, "Google, Inc."),
        (10, "Google Noto Color Emoji artwork with AURA color, gloss, and depth treatment."),
        (13, OFL_TEXT),
        (14, "https://openfontlicense.org"),
        (16, "AURA Emoji"),
        (17, "Regular"),
    ):
        set_name(font, name_id, value)

    destination.parent.mkdir(parents=True, exist_ok=True)
    font.save(destination, reorderTables=True)
    return transformed, unchanged


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    changed, unchanged = transform(args.source, args.destination)
    print(f"Saved {args.destination}: restyled {changed} embedded glyph images; skipped {unchanged}.")


if __name__ == "__main__":
    main()
