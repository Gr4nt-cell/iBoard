#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install using the Magisk app."
[ "${API:-0}" -ge 29 ] || abort "Android 10+ is required."
case "$ARCH" in
  arm64) ABI=arm64-v8a ;; arm) ABI=armeabi-v7a ;; x64) ABI=x86_64 ;; x86) ABI=x86 ;;
  *) abort "Unsupported ABI." ;;
esac
[ -s "$MODPATH/lib/$ABI/libTEESimulator.so" ] || abort "Missing native library."
[ -s "$MODPATH/lib/$ABI/libinject.so" ] || abort "Missing injector."
[ -s "$MODPATH/classes.dex" ] || abort "Missing runtime."
mv "$MODPATH/lib/$ABI/libTEESimulator.so" "$MODPATH/libTEESimulator.so"
mv "$MODPATH/lib/$ABI/libinject.so" "$MODPATH/inject"
rm -rf "$MODPATH/lib"
CFG=/data/adb/aura_integrity
mkdir -p "$CFG"
chmod 0700 "$CFG"
[ -e "$CFG/target.txt" ] || printf 'com.google.android.gms\ncom.android.vending\n' > "$CFG/target.txt"
[ -e "$CFG/security_patch.txt" ] || printf 'all=device_default\n' > "$CFG/security_patch.txt"
chmod 0600 "$CFG/target.txt" "$CFG/security_patch.txt"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for file in service.sh launch.sh action.sh daemon inject; do set_perm "$MODPATH/$file" 0 0 0755; done
ui_print "AURA Integrity candidate installed. No private keys are bundled."
ui_print "Inactive until your authorized keybox is installed and Action is pressed."
ui_print "Read README.md. A local validation success is NOT a Strong Integrity verdict."
