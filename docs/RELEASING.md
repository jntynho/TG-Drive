# Building and releasing 0.5.0

## GitHub Actions

[release-no-secrets.yml](../.github/workflows/release-no-secrets.yml), **Release APK (no secrets)**:

1. Optional sharing service tests on Node 24 and native device tests on an accelerated Android 30 x86_64 emulator. No account credentials are used.
2. Release unit tests, lint, R8/shrunk APK and AAB build.
3. Existing signature verification, ZIP and ELF alignment checks.
4. Validate that all standalone APKs contain identical DEX/resources/assets and byte-identical native libraries for their ABI. Upload each APK separately with its own checksum; AAB and mapping files have separate artifacts.
5. Install the published signed universal 0.1.1 APK on a disposable x86_64 emulator, seed synthetic private files/preferences, and install the new signed x86_64 APK with `adb install -r`. Check UID/data retention and release activity launch. No real Telegram session is created.
6. Publication waits for the upgrade check. Every main push creates a real Release with direct APK assets, tagged `v<version>-build.<run-number>`. Explicit version tags and manual publishing use `v<version>`. Assets finish uploading to a draft before it is made public.

| Artifact | Use |
| --- | --- |
| `tg-drive-arm64-v8a` | Most modern Android phones; one standalone APK plus checksum |
| `tg-drive-armeabi-v7a` | ARM32 phones; one standalone APK plus checksum |
| `tg-drive-universal` | Larger fallback including all four supported ABIs |
| `tg-drive-x86_64`, `tg-drive-x86` | Intel/emulator devices |
| `tg-drive-bundle` | AAB for store tooling; not directly installable |
| `tg-drive-diagnostics` | R8 mapping for developers; not needed to install |

Choose one APK matching the device. These are standalone APKs, not a set that must be installed together. All outputs share the application ID, version code/name and signing identity. Ordinary debug/emulator builds keep their existing universal packaging; opt into distribution APKs with `-PsplitApks=true`. ABI selection changes only packaging, not transfer, account, encryption or preview code.

Push to `main`, a `v…` tag, or workflow dispatch with `publish=true` creates a GitHub Release. Open Releases → latest → Assets and download a `.apk` directly; Actions artifacts are still ZIP archives and the automatic source ZIP/TAR files are not app installers. Default manual version is `0.5.0`; `APP_VERSION_CODE` is the workflow run number. Keep version codes monotonic. No Google Play upload is configured. An APK artifact is distinct from a published GitHub release; a locally compiled test APK is distinct from an executed device test.

## Existing signing identity

The verified public [0.1.1 APK](https://github.com/jntynho/TG-Drive/releases/tag/v0.1.1) has certificate SHA-256:

```text
bea43fa8797f48dbd55717f34454af1836167da85d9fe47445190a6561af3f9d
```

The value is pinned in `scripts/sideload-cert-sha256.txt`. It is public certificate information, not a signing key. The workflow retains cache `tgdrive-sideload-signing-key-v1`, path `~/.tgdrive-signing/release.jks`, alias `tgdrive`. It refuses to silently generate a different identity when cache is missing. An update must retain the signature, application ID and data paths to preserve installed sessions/indexes.

**Cache is not a durable keystore backup.** Securely back up the existing key. To restore the **same** identity from a backup, configure repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Secret-based recovery is optional while cache is valid. The APK fingerprint check also applies to a restored key. Do not invent a new key to make the check pass. A new distribution/signature is a deliberate separate migration and cannot update existing installs normally.

Gradle accepts `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` locally. Without all four, local release APK/AAB are unsigned. Debug signing differs from the workflow key; do not uninstall a user's existing application merely to install a debug build.

## Application API credentials

No Telegram developer credentials were supplied for this change. The app therefore retains manual API ID/hash setup. A distributor with an authorized Telegram application may supply `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` to the build through secure environment bindings or ignored `local.properties`; users then begin with a phone number. These are extractable application identifiers in the APK, not user sessions. SMS delivery and additional Telegram authentication steps are not guaranteed by the app.

## Local validation

```sh
./gradlew --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew --max-workers=2 testReleaseUnitTest lintRelease assembleRelease -PsplitApks=true
./gradlew --max-workers=2 bundleRelease -PbundleOnly=true
npm test --prefix sharing-server
python3 scripts/check_native_alignment.py app/build/outputs/apk/release/*.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

For signed output, also run `apksigner verify --verbose --print-certs`. Run `connectedDebugAndroidTest` on a connected emulator/phone. Fake-wire tests and native device smoke tests do not prove authenticated Telegram transfer, upgrade data preservation on a real install, multi-device synchronization or store-policy acceptance. See TESTING.md and SECURITY.md before public production distribution.

The separate `bundleOnly` build directory works around AGP’s multi-APK/resource-shrinking bundle limitation. Build the AAB separately with `-PbundleOnly=true`, without `splitApks`; neither task overwrites the other’s metadata or outputs.

Local packaging validation for 0.2.1: ARM64 APK approximately 39.7 MB (19.0 MB in a single-APK ZIP at compression level 6), ARM32 approximately 29.1 MB (16.9 MB ZIP), universal approximately 139 MB. These measurements are from unsigned local output; signed artifact sizes may vary slightly. All five APKs had identical code/resources/assets and matching per-ABI native bytes, and all 24 native-library entries passed alignment checks. Release unit tests: 61 passed; lint had no errors. Actual signed upgrade results are reported by the workflow's `upgrade-check` job; no authenticated Telegram session is used there.
