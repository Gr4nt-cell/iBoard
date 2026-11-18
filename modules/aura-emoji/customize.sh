#!/system/bin/sh
[ "${API:-0}" -ge 26 ] || abort "Android 8 or newer is required."
ui_print "AURA Emoji 4.0 - system font fallback"
ui_print "Configuring this device's own font XML (including Android 15+)."
mkdir -p "$MODPATH/system/fonts"
mv "$MODPATH/payload/AuraEmojiSystem.ttf" "$MODPATH/system/fonts/AuraEmojiSystem.ttf" || abort "Missing emoji font."
rmdir "$MODPATH/payload" 2>/dev/null
sh "$MODPATH/apply-fonts.sh" "$MODPATH" || abort "No supported system font configuration found. No system files were changed."
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in customize.sh apply-fonts.sh post-fs-data.sh action.sh; do
    set_perm "$MODPATH/$script" 0 0 0755
done
cat "$MODPATH/install.log"
ui_print "Reboot to apply. Disable other emoji/font modules to avoid competing overlays."
ui_print "Apps with bundled fonts, emoji images, or a separate downloadable font can still differ."
