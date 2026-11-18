#!/system/bin/sh
MODDIR=${0%/*}
CFG=/data/adb/aura_integrity
mkdir -p "$CFG"
chmod 0700 "$CFG"
if [ -f "$CFG/enabled" ]; then
    rm -f "$CFG/enabled"
    printf 'Disabled for the next boot. Reboot to fully remove native hooks.\n'
    exit 0
fi
if [ ! -s "$CFG/keybox.xml" ] || [ -L "$CFG/keybox.xml" ]; then
    printf 'No authorized keybox is configured. Nothing was activated.\n'
    printf 'Place your own authorized keybox at /data/adb/aura_integrity/keybox.xml\n'
    printf 'Keep it private and chmod 600 it. Do not upload it to chat or GitHub.\n'
    printf 'This module cannot generate Google-trusted hardware credentials.\n'
    exit 1
fi
touch "$CFG/enabled"
chmod 0600 "$CFG/enabled"
sh "$MODDIR/launch.sh" "$MODDIR" >/dev/null 2>&1 &
printf 'Validation/start requested. Check /data/adb/aura_integrity/status.txt as root.\n'
printf 'Local key validation cannot establish a Strong Integrity pass. Check a live Google verdict.\n'
