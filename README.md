# TG Drive

Android file manager for documents stored in **your own Telegram Saved Messages**. Kotlin, native Jetpack Compose/Material 3, English UI, dark mode and short navigation transitions. Version **0.2.0 is experimental**; Arabic UI and desktop/web clients are outside this implementation.

- Multiple uploads, persistent queue, priority, per-file retry/pause, unmetered/charging rules and approximate upload pacing.
- Nested folders, rename/move, tags, favorites, search/filter, grid/list, trash, content versions and reversible organization rules.
- SHA-256 verification, offline pins, cache budget, image/PDF/text previews, thumbnails, seekable Media3 audio/video and Android sharing.
- Upload-only backup of explicitly selected Android folders, receipts, scan history and backup-health notifications.
- Account-scoped encrypted local records, metadata journal/conflict resolution, legacy 0.1.1 migration, index export/import and cloud checkpoints.
- Device credential/biometric app lock, protected screenshots/notifications, encrypted vaults with a separate recovery key.
- Read-only Android document provider, manual document capture/trim/rotation/PDF generation, on-device Latin OCR and editable searchable text.
- Optional HTTPS WebDAV copy export with download verification, and expiring/password-protected/revocable public-copy links through the included [sharing service](sharing-server/README.md).

See [the feature-by-feature implementation and limits](docs/IMPLEMENTATION_STATUS.md), [security boundaries](docs/SECURITY.md), [validation](docs/TESTING.md), and [release instructions](docs/RELEASING.md).

## Build

JDK 21, Android SDK platform 36/build-tools 36.0.0; Android 8/API 26 or newer. Use the checked-in Gradle wrapper, whose distribution checksum is pinned.

```sh
./gradlew --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew --max-workers=2 testReleaseUnitTest lintRelease assembleRelease bundleRelease
npm test --prefix sharing-server
```

Set `ANDROID_HOME` or ignored `local.properties` `sdk.dir`. To use Telegram, create an application at <https://my.telegram.org/apps>, then enter its API ID/hash on the device. A distributor may supply `TELEGRAM_API_ID`/`TELEGRAM_API_HASH` through environment settings or ignored `local.properties`; then users begin with their phone number. Embedded application credentials are extractable. Never commit user sessions, login codes, passwords or signing keys. SMS delivery and extra login requirements are controlled by Telegram.

## Download and updates

[Release APK workflow](.github/workflows/release-no-secrets.yml) runs the sharing tests and native device smoke suite, then release unit/lint checks and builds APK/AAB artifacts. A push to `main` creates `tg-drive-release`; a version tag or an explicit manual publish creates a GitHub prerelease. It does not upload to Google Play.

The workflow preserves the existing 0.1.1 signing certificate and rejects a replacement key. Keep a secure backup of the existing key; Actions cache is not durable key management. Debug builds have a different signature and cannot update the workflow APK in place. See [RELEASING](docs/RELEASING.md).

## Practical limits

Telegram cloud chats are not end-to-end encrypted; the optional vault adds client-side content and metadata encryption. App lock alone does not encrypt Telegram files. Vault labels, sizes, part counts and timing remain visible. Keep recovery credentials outside the phone.

Uploads resume from completed parts; an active part can restart. Pacing delays parts and permits bursts; it is not an instantaneous bandwidth cap. Android can defer background jobs, and protected jobs need the vault unlocked. Native TDLib cache, offline pins, assembled previews and upload source copies consume separate space.

Public links require a real deployed HTTPS service. None is deployed by this repository change. Sharing a vault file explicitly exports a decrypted copy, which the chosen server can read. WebDAV also requires your own endpoint. Neither receives Telegram login sessions. Revocation cannot recall downloaded copies.

There is no unlimited-storage guarantee. Account/API limits apply. An index cannot recover content deleted from Telegram. Imported files without a known original hash are not described as original-content verified. Do not use the experimental build as the sole copy of important files.

Each cloud task already has an isolated checkout. Use this checkout; do not create Git worktrees unless explicitly requested.
