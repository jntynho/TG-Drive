# Security and privacy boundaries

TG Drive is an independent Telegram API client. No application backend is deployed. Files go from the device through TDLib to Telegram Saved Messages. The app requests no contacts, broad storage or media-library access. System document pickers grant access to selected input/output documents.

Telegram's cloud chats are not end-to-end encrypted. This version does not add file encryption. File names and folder metadata are stored in the document caption. A local Keystore-protected database key does not add encryption to cloud files. There is no app-level biometric lock in this version.

Session/database and staging files are in private application storage. The TDLib database encryption key and manually entered API credentials are encrypted with a per-install Android Keystore AES-GCM key. Android application backup is disabled. Downloaded TDLib cache and failed upload staging may remain until application data is cleared. Device/root compromise is outside these protections. No analytics, advertising SDK or developer-server error logging is included.

Account-scoped indexes contain file metadata; they are caches and can be rebuilt from marked messages. Empty folders cannot be restored to another device. Deleting a marked Telegram message is permanent. Keeping only one copy of important files is unsafe regardless of provider.

## Native dependency

`io.github.tdlib-android:core:0.1.1` is a **community prebuilt**, not an official Google/Telegram Android Maven distribution. It packages `org.drinkless.tdlib` and `libtdjni.so` for arm64-v8a, armeabi-v7a, x86 and x86_64. The published Maven POM identifies https://github.com/AkashPriyadarshii/tdlib-android as its source repository. The upstream TDLib project is https://github.com/tdlib/td.

The source URL was unavailable during initial implementation; binary/source reproducibility and the complete bundled native dependency inventory have **not** been audited. Before a public production release, obtain and review the exact source/build provenance and license inventory, or replace the community artifact with an audited build of pinned TDLib and its dependencies. The CI ABI/alignment test verifies packaging, not supply-chain trust. Maven HTTPS and the pinned Gradle distribution checksum are retained; verification is never disabled.

See THIRD_PARTY_NOTICES.md. Report security issues privately to the repository owner; do not attach session files, phone codes, API credentials, personal documents or signing keys to public issues.
