# Android 0.2.0 implementation

Scope follows the 34-item research report. Desktop/web client (#27) and Arabic UI/OCR expansion (#33 and Arabic portion of #30) are excluded at the user's request. No external sharing/WebDAV service was provided; those connections are optional. This is an experimental implementation, not a claim that all production acceptance checks passed.

| Research feature | Implemented Android behavior | Boundary |
|---|---|---|
| 1 Batch operations | Multiple inputs, multi-select move/trash/restore/purge, ZIP download | Up to 1,000 inputs per batch; Android grants determine source access |
| 2 Persistent transfer center | SQLite tasks, progress, outcomes, explicit retry/cancel and priority | Process interruption is visible; unacknowledged work is reconciled |
| 3 Pause/resume | Completed parts checkpointed; pending messages reconciled | Active upload part can restart; native cancellation may need connectivity |
| 4 Network/battery rules | Unmetered/charging/thermal gates, active-task monitoring, bounded retries, priority, average upload pacing | Polling/cooperative cancellation; a part can burst faster than the configured average; preview/playback are manual reads |
| 5 Index recovery | Versioned JSON export/import, cloud checkpoints and legacy migration | Metadata backup is separate from content; only matching accounts merge |
| 6 Integrity | Original/part SHA-256, full downloaded hash before verification | Legacy imports lack an original hash; streaming does not verify a whole file |
| 7 Trash | Restore, explicit permanent deletion, retention cleanup | Cleanup runs with eligible work, not a guaranteed server-side timer; imported originals are deleted on purge too |
| 8 Duplicates | Content-hash skip for an active version in the same encryption space, with a remote-reference preflight; explicit replacement versions | Full source hashing takes I/O; explicit rescans can request extra copies |
| 9 Storage | App cache/offline counts, soft budget with active leases, safe cache clearing, native cache inspection | TDLib cache and retained upload sources are separate; no automatic deletion of a sole original |
| 10 Folders | Nested folders, rename/move, cycle and vault checks | Moving a folder does not re-encrypt content; remove empty folders only |
| 11 Search | Name/tags/local OCR, type/size/date filters, favorites, sorting | OCR index is local; protected items require unlock |
| 12 Preview | Zoomable image, paginated PDF, bounded text and on-demand cloud thumbnails | Unsupported/password PDFs use external export; older imported items may have no thumbnail; video thumbnails require API 27+ |
| 13 Playback | Media3 seekable TDLib ranges, multipart/vault mapping, background media session, saved position | Codec/network dependent; buffering and seek need physical-device tests |
| 14 Offline | Verified raw parts pinned separately from disposable preview cache | A new content version needs a new pin; vault key still required |
| 15 Android sharing | Incoming share approval/destination, external copy chooser | Temporary source grants can require a private durable source copy |
| 16 Folder backup | SAF rules, recursive upload-only scans, incremental receipts, scheduling and explicit full rescan | Android-owned/private app data unavailable; 1,000 queued files per pass, select narrower rules above 100,000 scanned files |
| 17 Backup health | Last scan/file success, failures, notifications and retry | An unchanged scan does not fabricate a file-upload success; Android can defer jobs |
| 18 Metadata sync | Immutable operation journal, conflict preservation and explicit resolution; empty folders sync too | Provider history scans cost requests; no byte recovery after remote deletion; multi-device acceptance still pending |
| 19 Versions | Replace existing content, retain versions, restore earlier version | Stored versions consume space until permanent file deletion |
| 20 Import | User-selected Saved Messages document references, original captions retained | Imported content is not encrypted retroactively and cannot claim an unknown original hash |
| 21 App privacy | Device credential/biometric lock, protected recents/screenshots, generic notifications | Device compromise and already granted exported copies are outside app lock |
| 22 Encrypted vault | Tink authenticated streaming encryption, authenticated metadata, password and independent recovery wraps | Label/size/timing leak; no independent cryptographic audit; losing both credentials can lose access |
| 23 Local cleanup | Review selected originals only after downloaded verification, fresh stored-part checksums and source rehash, Android deletion consent | Offline/uncertain references never qualify; provider deletion support varies |
| 24 Diagnostics | Availability scan, full reconciliation, conflict tools and redacted report | Network failures are inconclusive; nothing is auto-deleted to “repair” an index |
| 25 Public links | Optional server/client, expiry/password/revoke, content hash, retry idempotence, explicit copy consent | Requires deployment/TLS/owner token; exported vault copies are plaintext to the server; no deployment performed |
| 26 Document picker | Read-only DocumentsProvider with account/app/vault checks | Materializes content before opening; writable provider integration is excluded |
| 28 Independent export | Portable files/ZIP plus index export, optional HTTPS WebDAV PUT then GET/hash | WebDAV is optional; no S3 provider in this build; exported ZIP opens without Telegram |
| 29 Large files | Ordered manifests, per-part/full hashes, logical multipart assembly, final metadata commit | Default 512 MiB parts, max 128; temporary space and Telegram limits apply; no bypass guarantee |
| 30 Documents/OCR | Camera/image selection, manual trim/rotation/grayscale PDF, bundled Latin OCR for images/up to 50 PDF pages, editable local text | Automatic edge detection and Arabic OCR are not implemented; accuracy needs a real document sample |
| 31 Organization | Preview type/month proposals, apply and undo last batch | Local explicit rules; no face recognition or cloud AI |
| 32 Accounts | Up to five profiles, separate sessions/settings/directories, account databases and scoped tasks | Same Telegram account in a second profile is refused; cross-account moves require an explicit re-upload |
| 34 Release/login | Preserve auth-order fix, native launch checks, optional build-time app API credentials, signed APK/AAB workflow and pinned 0.1.1 certificate | No developer API credentials supplied, so manual API setup remains; cache needs a secure key backup; SMS is provider controlled |

Native UI uses Compose/Material 3, lazy lists and approximately 180 ms fades/short slides. Compilation and screen smoke tests do not establish measured animation smoothness or accessibility coverage on real devices. See TESTING.md for the actual evidence and unrun acceptance matrix.
