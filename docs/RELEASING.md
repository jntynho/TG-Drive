# Building and releasing

## Current GitHub workflow

The repository currently uses `.github/workflows/release-no-secrets.yml`, named **Release APK (no secrets)**. It was added separately from the original workflows; the connection fix preserves its existing signing-key path, cache key and alias so builds can update installed test APKs when the same cached key is restored.

- **Push to main**: run release unit tests and lint, build and verify a signed test APK, upload `tg-drive-release`. This does not create a GitHub release.
- **Actions → Release APK (no secrets) → Run workflow**: select version `0.1.1` (or the next version), and set `publish` to control GitHub prerelease creation.
- **Push a version tag**: use the tag version and create a GitHub prerelease after successful validation/build.

Artifacts contain the APK, R8 mapping and SHA-256 checksums. `validation-reports` contains the unit/lint reports. There is no Google Play upload. Building an AAB locally remains supported with `./gradlew bundleRelease`, but the current workflow distributes an APK only.

Telegram application credentials are not required for the build: the installed app asks for API ID/hash. They can optionally be supplied as `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` to Gradle through the environment or ignored `local.properties`. They are application credentials, not a bot token, login code or user-session key. Anything embedded in an APK is extractable.

## Signing and updates

The current no-secrets workflow generates a sideload test key on its first run and reuses `~/.tgdrive-signing` from cache `tgdrive-sideload-signing-key-v1`. **Do not change/delete that key merely to fix Telegram initialization.** Changing signing identity prevents an in-place Android update. Cache retention is not guaranteed; cache-only key storage is unsuitable as the sole backup for production signing. The workflow's shared test password is not a production security boundary.

For a real production/upload key, use a backed-up keystore and repository secrets. Gradle already supports `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Migrate the workflow deliberately and account for installed users' signing identity; do not silently replace their signing key.

A local signed build uses:

```bash
./gradlew testReleaseUnitTest lintRelease assembleRelease bundleRelease
python3 scripts/check_native_alignment.py app/build/outputs/apk/release/*.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
```

Without signing settings, local release APK/AAB builds remain unsigned. Debug APKs use the debug signing identity and cannot directly update the separately signed workflow APK. For the reported connection bug, use the current workflow's new APK rather than uninstalling the old app to switch signing identities.

`APP_VERSION_NAME` and `APP_VERSION_CODE` can override Gradle defaults. The workflow uses its run number for versionCode; keep codes monotonic when migrating workflows. Retain mapping.txt for each distributed version.

## Validation boundaries

Unit tests cover reordered auth callbacks, timeouts and recovery through a fake transport, without account credentials or Telegram network access. They do not prove real account login, SMS delivery, or transfer success. Device smoke tests are checked in but are not run by the current no-secrets workflow. Run them on an accelerated emulator or physical test device, plus authenticated acceptance checks in TESTING.md, before public production distribution.

The local APK ZIP/native alignment checks do not establish store approval or native binary provenance. Review SECURITY.md, current Telegram API terms and Google Play policy before distribution beyond testing.
