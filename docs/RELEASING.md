# Building and releasing

## GitHub Actions

`Android checks` runs unit tests, lint, debug builds, and emulator smoke tests on pushes to main/work and pull requests. Fork PRs do not receive signing secrets.

`Android release` supports:

- **Actions → Android release → Run workflow**: build and download the `tg-drive-release` artifact. Without signing secrets the APK/AAB are unsigned, deliberately not signed with a debug or disposable key.
- **Push a version tag**: build signed APK/AAB and create a GitHub **prerelease** with checksums and the R8 mapping. Missing/incomplete signing fails the run. This workflow does not publish to Google Play.

The default `GITHUB_TOKEN` must be permitted to create releases by the repository/organization policy. No personal token is needed. Protect tag creation and keep signing secrets restricted to trusted maintainers.

## Required repository secrets for signed releases

Configure Settings → Secrets and variables → Actions:

| Secret | Meaning |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of your long-lived release/upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |
| `TELEGRAM_API_ID` | Optional Telegram application ID |
| `TELEGRAM_API_HASH` | Optional Telegram application hash |

If Telegram app credentials are omitted, the installed app asks for them on-device. These credentials are not a Telegram user session, phone login code, or bot token. Build-time app credentials can be extracted from APKs; do not treat the APK as a secret vault.

Create and back up a real key on a trusted machine (interactive prompts keep passwords out of shell history):

```bash
keytool -genkeypair -v -keystore tgdrive-release.jks -alias tgdrive \
  -keyalg RSA -keysize 3072 -validity 10000
base64 < tgdrive-release.jks | tr -d '\n' > keystore.base64
```

Copy `keystore.base64` into the secret editor, then securely remove the temporary encoding. Do not commit either file. Retain a secure backup of the original key and passwords; updates must use a compatible signing identity. For Google Play, use Play App Signing and a distinct upload key as appropriate.

After checks and manual device testing pass:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The tag provides versionName; the release workflow run number provides versionCode. Keep codes monotonic if migrating workflows or uploading existing versions to Play. Re-running the same successful tag does not overwrite an existing release; create a new version for a new release.

## Local signed build

Set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` securely in the environment or ignored `local.properties`. `RELEASE_STORE_FILE` should be an absolute path. Optional `APP_VERSION_NAME` and `APP_VERSION_CODE` override local version defaults.

```bash
./gradlew testReleaseUnitTest lintRelease assembleRelease bundleRelease
python3 scripts/check_native_alignment.py app/build/outputs/apk/release/*.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
```

Retain mapping.txt for each released version. The pipeline verifies native ELF and ZIP 16 KB alignment, but Play compliance additionally requires current policy checks, signing, Data Safety declarations and testing on supported devices. A successful build is not approval for store distribution.
