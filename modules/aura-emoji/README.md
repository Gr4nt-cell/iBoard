# AURA Emoji 4.0 Magisk module

This rebuild uses the existing AURA Emoji Full artwork/font and preserves the old module ID for in-place upgrades. It adds an independent `AuraEmojiSystem.ttf` fallback before the stock emoji family in the phone's own `fonts.xml` and Android 15+ `font_fallback.xml`. Stock Noto remains available for unsupported glyphs and Android's signed font updates. Existing OEM emoji TTF filenames receive systemless aliases, including fonts on product/system_ext/vendor where present.

Install the ZIP in Magisk's Modules screen and reboot. Disable competing emoji/font modules first. Use the module's Action button for diagnostics. Disable/remove the module and reboot to restore the original fonts. No system partition, font updater database, app data, or APK is modified. No Google service is disabled. The early boot hook regenerates configuration from the current ROM before Android builds its font fallback; unsupported configurations cause module mounts to be skipped.

## Coverage limit

This is system-wide **system-font coverage**, not a guarantee for every app. Apps can bundle their own fonts, download a separate EmojiCompat font, or draw emoji as images/stickers. A font-only module cannot force those renderers to change. Fonts inside APKs require app-specific integration or a separate runtime hook. Emoji sent to another person are still Unicode text and appear in the recipient's own style. Apps isolated through Magisk DenyList may not see module mounts. Restart apps or reboot after installing; cached spans/fonts do not change mid-process.

Only unique existing OEM `.ttf` emoji filenames are aliased; font collections (`.ttc`) are deliberately left alone because their collection indexes cannot safely be replaced with a single TTF. Configuration is patched structurally without replacing non-emoji font families, locale mappings or aliases. This module is built for Magisk 20.4+; other root managers have not been device-tested.

The build report records source font checksums, module identity, bitmap/GSUB checks, and the old ZIP's contents. Font shaping and XML/installer fixture checks run before publication. A successful CI run does not constitute a physical Magisk/HyperOS boot test.

Sources: Android font fallback customization https://source.android.com/docs/core/fonts/custom-font-fallback ; Magisk module guide https://topjohnwu.github.io/Magisk/guides.html ; Android EmojiCompat https://developer.android.com/develop/ui/views/text-and-emoji/emoji2 .
