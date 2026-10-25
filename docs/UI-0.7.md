# TG Drive 0.7 — media and single-message uploads

## Upload transport

An unencrypted, nonempty upload that fits in one configured part can carry its immutable VERSION and ITEM creation operations in a `TGDRIVE-FILE/3` document caption. The GZIP/Base64 caption is bounded to a conservative 1024 characters and 64 KiB decoded data. The two stable operation IDs, parent references and full manifest are persisted in the durable task **before** the send. A wire-only zero message ID is resolved to the owning document's actual ID before ordinary metadata validation and SQLite application. The creation operations are acknowledged atomically, without a separate metadata send. Subsequent renames, moves, Trash, and other edits still use the existing batched conflict-preserving journal.

The original bytes and extension are retained. Documents remain documents (`disableContentTypeDetection=true`) so Telegram cannot silently change the message into a photo/audio/video type that the existing verified storage reader cannot restore. A small locally generated JPEG (at most 200 KiB and 320 pixels) may be attached as `InputThumbnail` in **the same send request**; it is not another Saved Messages document. Telegram's returned document thumbnail is preferred over its tiny minithumbnail, with bounded downloads and decoding. Missing/unsupported previews remain honest placeholders; scrolling does not fetch full originals to obtain a preview. We cannot guarantee that Telegram itself generates thumbnails for every document.

Encrypted vaults, multipart files, empty files, resumed legacy uploads and captions exceeding the limit retain the existing transport. Vault previews remain encrypted; no public plaintext cover is attached. Source checksums are retained, and a changed single-part staging copy fails before sending. Server flood waits and durable cooldowns remain mandatory. Message reduction does not promise a particular transfer speed or bypass rate limits.

### Compatibility

0.7 reads previous metadata formats and does not migrate, delete or reupload existing files. **Update every device to 0.7 or later before relying on newly embedded uploads**: 0.6 and earlier cannot index `TGDRIVE-FILE/3` creation captions. Existing old upload tasks with pending messages keep their old path. Fresh/full history reconciliation reconstructs embedded records and part receipts, retains newer edits through stable operation IDs, and does not duplicate content.

## UI

- Gallery header search restored. No total photo/video count (selection count remains actionable). Gallery and Audio have anchored Material sort popups: upload date, name and size, with stable ID ties. Date grouping only applies to date sorts so it never overrides alphabetical/size ordering.
- Thumbnail pixels fade in over 180 ms in the existing grid cells; no layout/scale bounce.
- The fixed shared title bar uses bare Back arrows and a width/fade title transition. Search rows sit directly under it, inside their route's transition subtree. Root-to-detail motion therefore never changes the outer Scaffold inset; the outgoing search travels with its own page, eliminating simultaneous horizontal and vertical page motion.
- Photos/videos use the entire invariant viewport behind a transparent title bar. Only the filename appears, no position fraction/fullscreen button/floating close button. Zoom automatically hides chrome; tapping the image toggles it. System Back always remains available. Nonvisual previews measure their toolbar height, including system insets.
- Photo gestures track the fingers directly, zoom around the focal point, animate double taps over 240 ms, bound panning to the fitted image, and support inertial pan. Gallery paging is disabled while zoomed. Scale is limited to 1–6× over the bounded decoded preview; this is not full-resolution tiled image decoding.
- Video keeps Media3/ExoPlayer and private range reads, replacing stock playback widgets with Compose controls: play/pause/replay, ±10 seconds, scrubbing, elapsed/total time and 0.5–2× speed. Controls fade/auto-hide while playing, stay visible while paused, and pause with the activity. Only the active gallery page owns a player.
- Original Cloud Stack SVG has editable `background` and `logo` groups, with matching adaptive foreground/background and monochrome Android resources. The launcher controls mask/zoom behavior; SVG itself is resolution independent, not animated.

## Validation boundaries

Repository/queue tests use a fake Telegram connection and real SQLite/cache; they verify one message per eligible file, recovery, restoration, checksums, deletion and legacy multipart/vault behavior. Native gesture/popup tests run in the GitHub emulator. Local unit/lint/build checks and CI signature/upgrade checks are recorded with the release. No live Telegram credentials, physical-device frame pacing, or comprehensive codec matrix are implied.
