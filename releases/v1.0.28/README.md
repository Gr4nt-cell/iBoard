# iBoard 1.0.28

Built from commit 61bc18b (version code 28, targets Android 16 / API 36).

What's new: **Gemoji**, iBoard's built-in 3D emoji font (4,009 emoji incl. skin tones, families,
professions and flags), is now the default emoji style. "Phone's Emoji" is still available in
Settings › Emoji. Importing a custom emoji font has been removed.

Both files are signed with the **Play upload key** (alias `upload`, RSA 4096,
SHA-256 `A1:8A:17:FD:F9:D9:9D:0C:BD:7D:AA:BB:4C:EF:99:10:CD:B3:30:C4:71:84:A3:0E:59:1C:34:34:F5:DC:7E:39`).

| File | Use | Signature |
|---|---|---|
| `iBoard-v1.0.28-signed.aab` | Upload to Google Play Console | JAR signature (jarsigner, SHA256withRSA) |
| `iBoard-v1.0.28.apk` | Install directly on a phone | APK Signature Scheme v2 + v3, zipaligned |

Gemoji graphics are adapted from Twemoji (CC BY 4.0); the credit is in the app's About text and
in `assets/fonts/GEMOJI-LICENSE.txt`.
