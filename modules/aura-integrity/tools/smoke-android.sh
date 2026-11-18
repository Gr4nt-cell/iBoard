#!/usr/bin/env bash
set -euo pipefail
fixture=$(mktemp -d)
remote=/data/local/tmp/aura-integrity-ci
trap 'adb shell rm -rf "$remote" >/dev/null 2>&1 || true; rm -rf "$fixture"' EXIT
python aura/modules/aura-integrity/tools/fixture.py "$fixture"
python - "$fixture" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile('out/AURA-Integrity-Candidate-1.0.zip') as z:
    for name in ['classes.dex','roots.json']:(root/name).write_bytes(z.read(name))
PY
adb root
adb wait-for-device
adb shell mkdir -p "$remote"
adb shell chmod 700 "$remote"
adb push "$fixture/." "$remote/" >/dev/null
run_validator() {
    adb shell /system/bin/app_process -Djava.class.path="$remote/classes.dex" "$remote" org.matrix.TEESimulator.AuraKeyboxValidator "$1" "$2"
}
# Missing keys must fail before any network operation or native hook startup.
if run_validator "$remote/missing.xml" "$remote/roots.json" > "$fixture/missing.log"; then
    echo 'Missing credentials unexpectedly accepted';exit 1
fi
grep -q 'INACTIVE:' "$fixture/missing.log"
# This uses an ephemeral TEST root purely to exercise Android TLS, XML, and crypto APIs.
run_validator "$remote/keybox-fixture.xml" "$remote/fixture-roots.json" > "$fixture/local.log"
grep -q 'LOCAL_CREDENTIAL_CHECKS_PASSED' "$fixture/local.log"
# The production root set must reject those same software-only credentials.
if run_validator "$remote/keybox-fixture.xml" "$remote/roots.json" > "$fixture/untrusted.log"; then
    echo 'Untrusted software identity unexpectedly accepted';exit 1
fi
grep -q 'UNTRUSTED_ATTESTATION_ROOT' "$fixture/untrusted.log"
echo 'PASS: Android credential runtime; missing keys rejected; software-only identity rejected by production trust roots.'
echo 'No native hooks were activated. No hardware identity or Google verdict was tested.'
