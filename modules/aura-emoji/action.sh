#!/system/bin/sh
MODDIR=${0%/*}
printf 'AURA Emoji diagnostics\n\n'
cat "$MODDIR/install.log" 2>/dev/null
printf '\nAndroid font service (if supported):\n'
cmd font dump 2>/dev/null | grep -i -E 'AuraEmojiSystem|emoji' | head -30
printf '\nReboot after installation. Disable conflicting font modules.\n'
printf 'Bundled fonts, downloaded EmojiCompat fonts and emoji images are outside system fallback.\n'
printf 'Magisk DenyList-isolated apps may not see this module.\n'
printf 'Disable/remove the module in Magisk and reboot to restore stock fonts.\n'
