#!/usr/bin/env bash
set -euo pipefail
fixture=$(mktemp -d)
remote=/data/local/tmp/aura-integrity-ci
relay_pid=
trap '[ -z "$relay_pid" ] || kill "$relay_pid" 2>/dev/null || true; adb reverse --remove tcp:18743 >/dev/null 2>&1 || true; adb shell rm -rf "$remote" >/dev/null 2>&1 || true; rm -rf "$fixture"' EXIT
python aura/modules/aura-integrity/tools/fixture.py "$fixture"
python - "$fixture" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile('out/AURA-Integrity-Candidate-1.0.zip') as z:
    for name in ['classes.dex','roots.json']:(root/name).write_bytes(z.read(name))
PY
adb root
adb wait-for-device
python aura/modules/aura-integrity/tools/trust-relay.py > "$fixture/relay.log" 2>&1 &
relay_pid=$!
adb reverse tcp:18743 tcp:18743
adb shell mkdir -p "$remote"
adb shell chmod 700 "$remote"
adb push "$fixture/." "$remote/" >/dev/null
run_validator() {
    adb shell /system/bin/app_process -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18743 -Djava.class.path="$remote/classes.dex" "$remote" org.matrix.TEESimulator.AuraKeyboxValidator "$1" "$2"
}
# Exercise live public Google trust refresh; no private identities are fetched.
if ! run_validator --refresh-roots "$remote/refreshed-roots.json" > "$fixture/refresh.log"; then
    cat "$fixture/refresh.log";exit 1
fi
cat "$fixture/refresh.log"
# Missing keys must fail before any network operation or native hook startup.
if run_validator "$remote/missing.xml" "$remote/roots.json" > "$fixture/missing.log"; then
    echo 'Missing credentials unexpectedly accepted';exit 1
fi
grep -q 'INACTIVE:' "$fixture/missing.log"
# This uses an ephemeral TEST root purely to exercise Android TLS, XML, and crypto APIs.
if ! run_validator "$remote/keybox-fixture.xml" "$remote/fixture-roots.json" > "$fixture/local.log"; then
    cat "$fixture/local.log";exit 1
fi
grep -q 'LOCAL_CREDENTIAL_CHECKS_PASSED' "$fixture/local.log"
# The production root set must reject those same software-only credentials.
if run_validator "$remote/keybox-fixture.xml" "$remote/roots.json" > "$fixture/untrusted.log"; then
    echo 'Untrusted software identity unexpectedly accepted';exit 1
fi
grep -q 'UNTRUSTED_ATTESTATION_ROOT' "$fixture/untrusted.log"
echo 'PASS: Android credential runtime; missing keys rejected; software-only identity rejected by production trust roots.'
echo 'No native hooks were activated. No hardware identity or Google verdict was tested.'
