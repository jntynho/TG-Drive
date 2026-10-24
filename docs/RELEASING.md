# Building and releasing 0.2.0

## GitHub Actions

[release-no-secrets.yml](../.github/workflows/release-no-secrets.yml), **Release APK (no secrets)**:

1. Optional sharing service tests on Node 24 and native device tests on an accelerated Android 30 x86_64 emulator. No account credentials are used.
2. Release unit tests, lint, R8/shrunk APK and AAB build.
3. Existing signature verification, ZIP and ELF alignment checks.
4. Upload `tg-drive-release` with APK/AAB, R8 mapping and SHA-256 checksums; validation/device reports have separate artifacts.

Push to `main` builds artifacts. A `v…` tag or workflow dispatch with `publish=true` creates a GitHub prerelease. Default manual version is `0.2.0`; `APP_VERSION_CODE` is the workflow run number. Keep version codes monotonic. No Google Play upload is configured. An APK artifact is distinct from a published GitHub release; a locally compiled test APK is distinct from an executed device test.

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
./gradlew --max-workers=2 testReleaseUnitTest lintRelease assembleRelease bundleRelease
npm test --prefix sharing-server
python3 scripts/check_native_alignment.py app/build/outputs/apk/release/*.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
```

For signed output, also run `apksigner verify --verbose --print-certs`. Run `connectedDebugAndroidTest` on a connected emulator/phone. Fake-wire tests and native device smoke tests do not prove authenticated Telegram transfer, upgrade data preservation on a real install, multi-device synchronization or store-policy acceptance. See TESTING.md and SECURITY.md before public production distribution.
