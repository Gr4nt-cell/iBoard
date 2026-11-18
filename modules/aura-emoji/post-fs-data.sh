#!/system/bin/sh
# Magisk runs this before module mounts and before Android initializes its font cache.
# Refresh from the current ROM on each boot so an OTA cannot leave stale font XML behind.
MODDIR=${0%/*}
if sh "$MODDIR/apply-fonts.sh" "$MODDIR"; then
    rm -f "$MODDIR/skip_mount"
else
    # Fail closed: never mount an unsupported/stale system font configuration.
    touch "$MODDIR/skip_mount"
    printf '\nFont configuration failed. Module mounts skipped. Reinstall or remove this module.\n' >> "$MODDIR/install.log"
fi
