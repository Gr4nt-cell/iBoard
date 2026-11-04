# AURA Emoji

The keyboard renders the user-supplied SamsungColorEmoji color font after restyling it as the bundled **AURA Emoji** font. The app contains 4,462 transformed CBDT glyph images, with 3,549 of 3,564 tested picker sequences verified by the coverage check.

Unsupported emoji sequences are hidden by the picker and renderer; they do not fall back to Android's system emoji font. Font import and style-selection controls are removed. Unicode emoji inserted into other apps are rendered by those apps.

See `app/src/main/assets/fonts/FONT-NOTICE.md` for source attribution, licensing and the restyling details.
