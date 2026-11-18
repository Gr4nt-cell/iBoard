# AURA Integrity 1.0 candidate

A self-contained Magisk attestation-compatibility candidate using the GPLv3 TEESimulator engine, built from source at a pinned commit. No other attestation module or Zygisk installation is required by this engine. This is a repackaged, modified open-source engine, not an original hardware TEE and not a guarantee of `MEETS_STRONG_INTEGRITY`.

## Required external input

You must possess an authorized, unrevoked hardware attestation keybox and corresponding certificate chain. This ZIP contains **no private keybox**, no sample/test signing identity, no key downloader, and no key-extraction tool. Self-signed keys and public AOSP software test keys cannot provide Google's hardware trust. Root-certificate trust and revocation checks are necessary but cannot prove that Google will accept a requested verdict.

1. Disable other TrickyStore/TEESimulator-style keystore interception modules and reboot. AURA does not remove them or copy their credentials.
2. Install this ZIP through Magisk and reboot. Without configured credentials it stays inactive.
3. On the phone, place only your own authorized credentials at `/data/adb/aura_integrity/keybox.xml`, owned by root with mode `0600`. Do not upload private keys to chat, GitHub, or a key-sharing service.
4. Press the module's Action button once to request activation. It validates key/certificate matching, chain trust against Google's public roots, validity dates, and Google's current public revocation list. Missing credentials or failed checks keep the native engine inactive. Network failure also blocks startup.
5. Read `/data/adb/aura_integrity/status.txt` as root. `RUNNING` only reports engine startup. Obtain a real, fresh Play Integrity verdict through an appropriately configured checker/app; the module does not generate fake green verdicts or alter checker UIs.

The Action button subsequently disables startup for the next boot. Reboot to fully remove native hooks. For rollback, disable/remove AURA Integrity in Magisk and reboot. Credentials and local settings are retained under the private configuration directory; remove that directory manually only if you want to erase them.

## Scope and limitations

The default scope is Google Play services and Play Store. Real patch levels are retained (`all=device_default`); no fake future security-patch date, fingerprint, or global system property is installed. No GMS/Store app data is cleared, no Google component is disabled, and no bootloader relock is performed. The engine uses native keystore interception and requires its upstream SELinux rules. This can affect device security and app compatibility; an unsupported ROM may reject injection even after local key validation. Android 17/HyperOS support is a candidate until tested on the actual device.

Google can reject a verdict for certification, root detection, hardware state, security-patch age, app licensing, revocation or other server-side rules. A successful build and local key validation do **not** mean Strong Integrity has passed. This cannot mint a Google-trusted identity or make a rooted phone genuinely hardware-attested as an unmodified phone.

## Source and privacy

The build pins TEESimulator and its LSPlt submodule, publishes a corresponding-source archive, and records hashes and changes. Backend source: https://github.com/JingMatrix/TEESimulator (GPLv3). Runtime configuration was moved to `/data/adb/aura_integrity`; no existing `/data/adb/tricky_store` credentials are accessed. Debug/verbose backend logs are disabled in the release configuration. Only Google's public roots and revocation JSON are fetched at activation; no private-key bytes or certificate serials are sent to those endpoints.

Google roots: https://android.googleapis.com/attestation/root
Revocation data: https://android.googleapis.com/attestation/status
Official verification guidance: https://developer.android.com/privacy-and-security/security-key-attestation

## Google server refresh

Before each activation, the module refreshes Google's public attestation trust roots over HTTPS from `https://android.googleapis.com/attestation/root`. It also fetches the public revocation list from `https://android.googleapis.com/attestation/status` during credential validation. An unavailable endpoint or invalid response prevents activation. Refreshed public roots are stored privately at `/data/adb/aura_integrity/roots.json`. The bundled root snapshot remains available for inspection but is not used as a silent fallback for a failed refresh.

These public endpoints do not distribute private keyboxes. Google's Remote Key Provisioning issues certificates for keys generated inside supported secure hardware; it is not a downloadable Magisk keybox service. No keybox search, harvesting, extraction, or third-party key source is included. Your authorized private credential remains local.

CI uses a loopback CONNECT relay to work around emulator DNS. Google TLS remains end-to-end and uses normal certificate verification; no custom CA, plaintext fixture response, or private credential is sent through it. This relay is a build-test tool and is not installed on the phone.
