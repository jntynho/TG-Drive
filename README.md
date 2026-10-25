# TG Drive

Android file manager for documents stored in **your own Telegram Saved Messages**. Kotlin, native Jetpack Compose/Material 3, English UI, dark mode and short navigation transitions. Version **0.8.0 is experimental**; Arabic UI and desktop/web clients are outside this implementation.

- Multiple uploads, persistent queue, priority, per-file retry/pause, unmetered/charging rules and approximate upload pacing.
- Nested folders, rename/move, tags, favorites, search/filter, grid/list, trash, content versions and reversible organization rules.
- SHA-256 verification, offline pins, cache budget, image/PDF/text previews, thumbnails, seekable Media3 audio/video and Android sharing.
- Upload-only backup of explicitly selected Android folders, receipts, scan history and backup-health notifications.
- Account-scoped encrypted local records, metadata journal/conflict resolution, legacy 0.1.1 migration, index export/import and cloud checkpoints.
- Device credential/biometric app lock, protected screenshots/notifications, encrypted vaults with a separate recovery key.
- Read-only Android document provider, manual document capture/trim/rotation/PDF generation, on-device Latin OCR and editable searchable text.
- Optional HTTPS WebDAV copy export with download verification, and expiring/password-protected/revocable public-copy links through the included [sharing service](sharing-server/README.md).

See [the feature-by-feature implementation and limits](docs/IMPLEMENTATION_STATUS.md), [security boundaries](docs/SECURITY.md), [validation](docs/TESTING.md), and [release instructions](docs/RELEASING.md).

The [native visual design](docs/DESIGN.md) applies the supplied Muse Android reference to the native UI: charcoal surfaces, grouped rounded settings, blue pill buttons, filled fields and quiet transitions. It uses the system font and adds no runtime libraries or image assets.

## Build

JDK 21, Android SDK platform 36/build-tools 36.0.0; Android 8/API 26 or newer. Use the checked-in Gradle wrapper, whose distribution checksum is pinned.

```sh
./gradlew --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew --max-workers=2 testReleaseUnitTest lintRelease assembleRelease bundleRelease
npm test --prefix sharing-server
```

Set `ANDROID_HOME` or ignored `local.properties` `sdk.dir`. To use Telegram, create an application at <https://my.telegram.org/apps>, then enter its API ID/hash on the device. A distributor may supply `TELEGRAM_API_ID`/`TELEGRAM_API_HASH` through environment settings or ignored `local.properties`; then users begin with their phone number. Embedded application credentials are extractable. Never commit user sessions, login codes, passwords or signing keys. SMS delivery and extra login requirements are controlled by Telegram.

## Download and updates

[Release APK workflow](.github/workflows/release-no-secrets.yml) runs the sharing tests and native device smoke suite, then release unit/lint checks, verifies matching payloads across ABI APKs, and checks a signed in-place upgrade on an emulator. APKs, AAB and developer mapping files are separate downloads. A push to `main` creates separate APK artifacts (`tg-drive-arm64-v8a`, `tg-drive-armeabi-v7a`, and universal/Intel fallbacks); a version tag or an explicit manual publish creates a GitHub prerelease. It does not upload to Google Play.

The workflow preserves the existing 0.1.1 signing certificate and rejects a replacement key. Keep a secure backup of the existing key; Actions cache is not durable key management. Debug builds have a different signature and cannot update the workflow APK in place. See [RELEASING](docs/RELEASING.md).

## Practical limits

Telegram cloud chats are not end-to-end encrypted; the optional vault adds client-side content and metadata encryption. App lock alone does not encrypt Telegram files. Vault labels, sizes, part counts and timing remain visible. Keep recovery credentials outside the phone.

Uploads resume from completed parts; an active part can restart. Pacing delays parts and permits bursts; it is not an instantaneous bandwidth cap. Android can defer background jobs, and protected jobs need the vault unlocked. Native TDLib cache, offline pins, assembled previews and upload source copies consume separate space.

Public links require a real deployed HTTPS service. None is deployed by this repository change. Sharing a vault file explicitly exports a decrypted copy, which the chosen server can read. WebDAV also requires your own endpoint. Neither receives Telegram login sessions. Revocation cannot recall downloaded copies.

There is no unlimited-storage guarantee. Account/API limits apply. An index cannot recover content deleted from Telegram. Imported files without a known original hash are not described as original-content verified. Do not use the experimental build as the sole copy of important files.

Each cloud task already has an isolated checkout. Use this checkout; do not create Git worktrees unless explicitly requested.

### Direct APK downloads

Open [the latest GitHub Release](https://github.com/jntynho/TG-Drive/releases/latest) and choose the `arm64-v8a.apk` asset for most modern phones. Each successful main build publishes signed APK assets after device tests and an upgrade check. You do not need the source ZIP or the Actions artifact ZIP. Install the APK over the existing app to retain its data.

The bottom navigation is Home / Gallery / Audio / Settings. Home includes recent uploads and links to Browse all files, Activity and Backups. Gallery shows image/video thumbnails in a grid; Audio collects songs and recordings. Pages use horizontal navigation transitions.

The 0.5 update adds adaptive page titles and Telegram account avatars, a square-corner 4:5 gallery, immersive image zoom, a dedicated gallery video player, Lucide interface icons, and an original [SVG app icon](docs/brand/tg-drive-icon.svg). The [UI review in Arabic](docs/UI-REVIEW.ar.md) records the source audit and the [upload pipeline](docs/TRANSFER-PIPELINE.md) explains batching, cooldowns and recovery. Update all TG Drive devices to 0.5+ to read newly batched cloud metadata.

The 0.6 interface places account management in Settings and search in route headers (Home, all files, Audio and Settings). Gallery has no search field. Home displays active indexed bytes, file count, type distribution and offline-copy size without inventing a Telegram quota. Audio reads embedded cover art when available. Gallery supports type/date groups, selection and in-viewer paging; the audio mini-player remains available while browsing. The removed Organize, Conflict and Optional connection settings do not delete existing library records or credentials. See [the 0.6 implementation notes](docs/UI-0.6.md).

The [0.7 update](docs/UI-0.7.md) restores Gallery header search, adds Gallery/Audio sort, fades thumbnails, stabilizes page geometry and adds a transparent media overlay, animated focal-point photo zoom and Compose video controls. Eligible unencrypted single-part files embed creation metadata in their own Telegram message and attach a thumbnail in the same request when supported. **Update all devices to 0.7+ to read these new creation captions.** Earlier files, encrypted vaults and multipart uploads remain readable; Telegram rate limits still apply.

The [0.8 upload pipeline](docs/UPLOAD-0.8.md) overlaps up to two ordinary content uploads while serializing final message publication. It reduces snapshot/staging reads, preserves account cooldowns and per-file pause/retry, and keeps vaults/paced work exclusive. It retains the 0.7 cloud format and adds no runtime dependency. Speed remains network/server dependent.
