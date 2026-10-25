# 0.8 upload pipeline

## Scheduling and isolation

The queue admits at most two ordinary unencrypted uploads concurrently. A free slot is filled when either job finishes; a slow peer does not hold a whole two-file batch. Vault uploads, explicit upload pacing, non-upload tasks and two replacements of the same logical item remain exclusive. Moderate Android thermal pressure lowers new admission to one; severe pressure retains the existing pause gate. Network, charging, vault, account and profile checks remain in effect.

Each active task owns its coroutine, native file ID and progress counters. Pause cancels and joins that task, not its sibling. Late progress updates use a repository transaction and cannot replace newer manifests, retry receipts or paused/completed state. Same-content uploads share a bounded striped lock and repeat the existing SHA-256 duplicate check before publication; intentional duplicates remain supported.

## Content before publication

TelegramStorage uses TDLib `PreliminaryUploadFile` with document type and waits for its remote upload completion before entering the account-wide message lane. The final `SendMessage` references `InputFileId`; it retains document semantics, embedded creation metadata and optional attached thumbnail. The two preliminary uploads can overlap while message publication remains serialized and paced. `GetFile` polling is a local TDLib-state lookup; native `UpdateFile` events supply progress. No Grammers, Rust, FFmpeg or additional runtime library is added.

Every exposed 429/FLOOD_WAIT from the content or publication stage persists the full account deadline plus the existing margin. Preliminary polling checks that deadline too; active preliminary requests are released on cancellation/failure, and no new sends bypass it through Retry. Cancellation calls `CancelPreliminaryUploadFile` only for the matching native ID while the same Telegram user is still connected. It never deletes an acknowledged document. Telegram/TDLib can still apply internal retries and scheduling; this is not a claim of fixed network concurrency or elimination of server limits.

## Fewer local reads

For eligible sources up to 64 MiB, a single streaming pass creates and hashes a private snapshot. The two active jobs reserve a conservative disk budget with a 128 MiB margin before opting into snapshots. Sources without reliable small size or sufficient free space use the streaming/hash-and-part fallback. Vaults never gain a plaintext snapshot.

A whole-file plain snapshot is reused directly for upload, avoiding a second staging copy and another full hash read in the same fresh attempt. Larger multipart copies calculate stored SHA-256 while writing the part (or ciphertext), instead of rereading the newly written file. Retry still verifies a saved snapshot against its durable size and SHA-256; the original changing after snapshot creation cannot change the retained upload. Changed/tampered snapshots fail before another part is sent. Existing 0.7 `source.snapshot` files remain readable.

The original source URI and permissions are retained for local cleanup validation. An immutable snapshot is the selected upload's bytes; an independently edited original is not deleted merely because the older snapshot was backed up.

## Compatibility and evidence

The cloud format remains `TGDRIVE-FILE/3` plus existing v2 metadata and encrypted/multipart records. 0.7+ clients can read 0.8 uploads; older data is not migrated or reuploaded. The existing signing identity and data paths remain unchanged.

Controlled integration tests use real SQLite, staging files and verified restoration with a fake TDLib boundary. They cover two pending native uploads before any publication, pause isolation, a three-file slot refill, duplicate suppression, full content-stage/server cooldown, immutable source preservation, snapshot tamper rejection, missing-snapshot source mutation, old multipart recovery/deletion and encrypted restoration. Coordinator tests cover lane independence and a full 900-second server wait. Local compile/lint tests do not prove authenticated Telegram performance or finer-grained native restart resumption; completed logical parts and pending-message reconciliation remain the durable guarantee. The active native upload may be reused or restarted by TDLib.
