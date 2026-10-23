# iBoard 1.0.23

Built from commit d091ec5 (version code 23, targets Android 16 / API 36, arm64).

Both files are signed with the **Play upload key** (alias `upload`, RSA 4096,
SHA-256 `A1:8A:17:FD:F9:D9:9D:0C:BD:7D:AA:BB:4C:EF:99:10:CD:B3:30:C4:71:84:A3:0E:59:1C:34:34:F5:DC:7E:39`).

| File | Use | Signature |
|---|---|---|
| `iBoard-v23-release.aab` | Upload to Google Play Console | JAR signature (jarsigner, SHA256withRSA) — the format Play requires for bundles |
| `iBoard-v23.apk` | Install directly on a phone | APK Signature Scheme v2 + v3 (apksigner), zipaligned, native libs 16 KB-aligned |

The APK uses a different key from earlier GitHub builds (1.0.19 and before), so uninstall
the old iBoard once before installing it.

## Play Console pre-launch checks

- **DEX optimisation / obfuscation:** R8 shrinking and obfuscation on (about 91% of classes renamed);
  the deobfuscation mapping file is inside the bundle, so Play Console shows readable crash reports.
- **Deprecated edge-to-edge APIs:** no calls to `Window.setStatusBarColor` / `setNavigationBarColor`
  and no `android:statusBarColor` / `navigationBarColor` attributes anywhere in the app.
- **Large screens:** no orientation, resizability or aspect-ratio limits in the manifest.
