# Security and privacy boundaries

TG Drive is an independent Telegram API client. Normal files go through on-device TDLib to the user's Saved Messages. There is no mandatory application backend, custom analytics pipeline or automatic support upload. Bundled Google ML Kit processes OCR images on device; its SDK diagnostics are governed by [Google ML Kit terms/privacy](https://developers.google.com/ml-kit/terms), which are separate from TG Drive support reports. System document pickers grant access to selected documents/folders; no broad storage/contacts permissions are requested. Android backup is disabled.

## Local protection and accounts

TDLib database keys, API settings and optional service credentials use profile-specific Android Keystore protection. Library row payloads use account-specific AES-GCM keys with table/row associated data; IDs and entity kinds remain queryable. Queues and source URIs are private. Original 0.1.1 session paths are retained. Additional accounts have independent TDLib directories/settings and account-scoped databases/caches. Duplicate profiles for the same Telegram user are refused.

App lock uses device credentials/strong biometrics after 30 seconds away. It controls the UI/document provider; upload-only work on ordinary files may continue. Vault work is paused and vaults locked when background locking runs. Protected playback stops on explicit lock. Notifications hide details by default and always hide vault filenames. Root/device compromise, memory forensics and copies already exported to another app are outside these protections.

## Vault format

Telegram cloud chats alone are **not end-to-end encrypted**. Optional vaults add Tink AES256_GCM_HKDF_1MB authenticated streaming encryption per part, including thumbnails. Per-vault AES-GCM metadata authenticates operation identity/parent/type/device/time/vault/deletion fields. Keys are randomly generated; portable key material is wrapped independently by PBKDF2-HMAC-SHA256 (600,000 iterations, random salt) password-derived AES-GCM and by a random 256-bit recovery key. Recovery must be kept outside the phone. Telegram SMS cannot recover a lost vault key.

Labels, account identity, object/part sizes, counts, timing and access patterns remain visible. File content/names are protected only inside the vault. Lock clears opened keys and assembled plaintext previews; processing checks cancellation and vault availability before committing previews. Managed runtimes cannot guarantee erasure of every temporary key/string copy. This implementation has **not received an independent cryptographic audit**. Maintain a separate recovery-tested copy before relying on it.

## Exports, cache and deletion

WebDAV and public links are explicit decrypted-copy exports, including vault files; UI asks for consent. Endpoints require HTTPS, normal certificate validation, no redirects or URL-embedded credentials. Jobs bind their endpoint, credentials and content version when approved. WebDAV uses a unique item/version path and verifies a downloaded copy. Sharing uses an optional separate copy-hosting server; no Telegram sessions or API login credentials leave the device for that service. Link passwords are scrypt-hashed; expiration/revocation prevent future downloads, not recovery of already downloaded copies.

Index exports contain ordinary names/metadata and encrypted vault descriptors, never TDLib sessions or device-only source URIs. App cache, verified offline parts, native TDLib cache, upload staging, temporary grants and explicit external exports are distinct. A soft cache budget protects active versions/offline data. Failed/cancelled upload sources can be retained for recovery; cancelling may leave acknowledged orphan parts in Saved Messages. The app does not silently delete sole originals.

Trash retains remote content until explicit/retention purge. Permanent deletion includes imported original messages and versions. An index checkpoint cannot recreate deleted bytes. Conflicts/deletion markers prevent stale metadata restoring a deleted file automatically; an explicit user restore or full rescan is a separate action.

## Dependencies and distribution

`io.github.tdlib-android:core:0.1.1` is a **community prebuilt**, not an official Telegram Android Maven distribution. Its POM identifies <https://github.com/AkashPriyadarshii/tdlib-android>; upstream is <https://github.com/tdlib/td>. Exact native source/build reproducibility and complete bundled dependency inventory have not been independently audited. Packaging/alignment tests do not establish supply-chain trust. Before public production distribution, audit a pinned source build and license inventory or replace this artifact with an audited build.

Release signatures are pinned to the public 0.1.1 APK certificate. The existing Actions-cache key is preserved; a missing/wrong key fails instead of silently rotating. Cache-only storage is not durable or suitable production key management. Store an independent secure backup, and use repository signing secrets for recovery of the same identity. Embedded Telegram application API credentials are extractable from APKs.

See THIRD_PARTY_NOTICES.md. Report issues privately to the repository owner. Do not attach sessions, login codes, documents, credentials, recovery keys or signing keys to public issues.
