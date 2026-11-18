#!/system/bin/sh
MODDIR=$1
CFG=/data/adb/aura_integrity
mkdir -p "$CFG"
chmod 0700 "$CFG"
status() { printf '%s\n' "$1" > "$CFG/status.txt"; chmod 0600 "$CFG/status.txt"; }
[ -f "$CFG/enabled" ] || { status 'INACTIVE: press module Action after configuring your authorized keybox.'; exit 0; }
[ -s "$CFG/keybox.xml" ] && [ ! -L "$CFG/keybox.xml" ] || { status 'INACTIVE: authorized keybox.xml is missing or is a symlink.'; exit 0; }
[ ! -L "$CFG/target.txt" ] && [ ! -L "$CFG/security_patch.txt" ] || { status 'INACTIVE: configuration symlinks are not supported.'; exit 1; }
for module in /data/adb/modules/*; do
    [ "$module" = "$MODDIR" ] && continue
    [ -f "$module/disable" ] || [ -f "$module/remove" ] && continue
    [ -f "$module/module.prop" ] || continue
    if grep -iqE '^(id|name)=.*(tricky.?store|tee.?simulator)' "$module/module.prop"; then
        status 'INACTIVE: disable other keystore interception modules and reboot first.'
        exit 1
    fi
done
if [ -f "$CFG/engine.pid" ]; then
    old=$(cat "$CFG/engine.pid")
    case "$old" in *[!0-9]*|'') ;; *)
      if [ -r "/proc/$old/cmdline" ] && tr '\000' ' ' < "/proc/$old/cmdline" | grep -q 'AuraIntegrity'; then
          status 'RUNNING: native engine active; actual Google Play Integrity verdict is untested.'; exit 0
      fi ;;
    esac
fi
chown 0:0 "$CFG/keybox.xml"
chmod 0600 "$CFG/keybox.xml"
# Only public revocation status is downloaded. Key material never leaves this device.
if ! /system/bin/app_process -Djava.class.path="$MODDIR/classes.dex" "$MODDIR" org.matrix.TEESimulator.AuraKeyboxValidator "$CFG/keybox.xml" "$MODDIR/roots.json" > "$CFG/check.tmp" 2>/dev/null; then
    mv -f "$CFG/check.tmp" "$CFG/status.txt"
    chmod 0600 "$CFG/status.txt"
    exit 1
fi
mv -f "$CFG/check.tmp" "$CFG/status.txt"
chmod 0600 "$CFG/status.txt"
# Limit retries rather than repeatedly destabilizing keystore on an unsupported ROM.
count=0
while [ "$count" -lt 3 ] && [ -f "$CFG/enabled" ]; do
    "$MODDIR/daemon" "$MODDIR" >/dev/null 2>&1 &
    engine=$!
    printf '%s\n' "$engine" > "$CFG/engine.pid"
    chmod 0600 "$CFG/engine.pid"
    status 'RUNNING: candidate engine started. This is NOT confirmation of Strong Integrity.'
    wait "$engine"
    rm -f "$CFG/engine.pid"
    count=$((count + 1))
    sleep 2
done
status 'STOPPED: engine exited. Disable this module and reboot before retrying.'
