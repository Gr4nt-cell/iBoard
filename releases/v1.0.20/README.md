# iBoard 1.0.20

Built from commit 02a6777 (version code 20, targets Android 16 / API 36, arm64).

Both files are signed with the **Play upload key** (alias `upload`, RSA 4096,
SHA-256 `A1:8A:17:FD:F9:D9:9D:0C:BD:7D:AA:BB:4C:EF:99:10:CD:B3:30:C4:71:84:A3:0E:59:1C:34:34:F5:DC:7E:39`).

| File | Use | Signature |
|---|---|---|
| `iBoard-v20-release.aab` | Upload to Google Play Console | JAR signature (jarsigner, SHA256withRSA) — the format Play requires for bundles |
| `iBoard-v20.apk` | Install directly on a phone | APK Signature Scheme v2 + v3 (apksigner), zipaligned, native libs 16 KB-aligned |

The APK uses a different key from earlier GitHub builds (1.0.19 and before), so uninstall
the old iBoard once before installing it.
