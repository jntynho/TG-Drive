# Validation and acceptance — Android 0.4.0

## Automated workflow

```sh
./gradlew --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew --max-workers=2 testReleaseUnitTest lintRelease assembleRelease bundleRelease
npm test --prefix sharing-server
./gradlew --max-workers=2 connectedDebugAndroidTest
python3 scripts/check_native_alignment.py app/build/outputs/apk/release/*.apk
```

Unit tests include the actual repository, SQLite database, queue and cache with a fake TDLib transport: multipart upload/restore byte comparison, interrupted retry without re-sending completed parts, changed source rejection, duplicate identity/hash preservation, rejection of trashed/purged/missing duplicate references, legacy migration, invalid metadata isolation, destination binding, active-delete refusal, corrupted-download rejection, cache leases/offline pins, stale checkpoint tombstones, encrypted multipart recovery, priority, pacing and bounded native-request deadlines. Separate tests cover crypto context/truncation/recovery, operation conflicts, transaction rollback, Unicode/path validation and authentication ordering/errors/timeouts. No unit test signs in to a real Telegram account.

The device suite expects English. It tests native TDLib loading/execution, authentication privacy/error controls, explicit delete cancellation in the legacy view, actual MainActivity startup/account sheet, native grid action separation, and accessible selection/long press. It does not exercise an authenticated cloud library. CI requires this suite on a KVM emulator before the signed build job.

The optional sharing server tests owner authentication, path/name validation, exact bytes, password enforcement, expiry, revocation, size/hash rejection, HTML escaping and idempotent upload retries. Loopback HTTP tests do not establish a deployed HTTPS service or production scalability.

The earlier 0.3.0 visual refresh added three Robolectric/native-graphics Compose cases:
all nine grouped settings routes remain reachable, switch rows expose a single
labelled control and retain toggled state, and phone sign-in gates Continue and
forwards the entered number unchanged. The cases generate synthetic-state
previews in `app/build/design-previews/`, included in CI's `validation-reports`.
These renders were visually reviewed against the supplied Muse Android images;
they do not measure real-device frame timing. See [the design reference](DESIGN.md).

## Actual environment limits

The local cloud machine has no `/dev/kvm`. A previous software-emulated Android 30 guest booted slowly then lost its system server before app installation. Local instrumentation APK compilation is useful, but **local device tests have not executed**; do not report them as passed. Device assertions must be assessed from the current GitHub Actions run or a physical-device run, separately from local compilation.

No real Telegram application credentials, account login, phone code, test account, WebDAV endpoint or deployed sharing origin was provided. No authenticated Telegram upload/download, SMS delivery, real two-device recovery, biometrics, actual provider attachment or release-upgrade data migration has been tested on a phone. These are pending acceptance checks, not passes inferred from builds.

## Physical-device / authenticated acceptance

Use a disposable test account and known-byte files; never irreplaceable personal data.

1. Install the previous signed release, create files/folders and sign in. Update with the new workflow APK without uninstalling. Compare the certificate and verify the session, existing file references and folders survive migration without a content re-upload.
2. Exercise invalid code/password, network loss, delayed auth callbacks, retry and edit configuration. Confirm no permanent connecting spinner and no secrets in diagnostics/logs. SMS timing is controlled by Telegram.
3. Upload text/image/video/PDF, empty files, Unicode names and multipart files. Download and compare independent SHA-256 hashes. Confirm originals are sent as documents without recompression.
4. Pause near a part boundary; cut network; change unmetered/charging state; kill the process; resume and inspect Saved Messages. Confirm completed parts are retained, a pending send is reconciled, active-part restart is stated and failures are not called complete. Test urgency and average pacing bursts.
5. Create/rename/move nested folders, tags, favorites, filters, selection and trash. Restore a disposable item, then permanently delete it. Check imported-original deletion consent. Merge an old checkpoint and ensure it does not resurrect deleted content automatically.
6. On two independent test devices, sync folders/metadata, create conflicting offline edits and resolve them. Network/history errors must preserve valid local records; unreadable messages must be reported rather than blocking the entire scan.
7. Pin files, enable airplane mode, preview and verify them. Clean unpinned cache and confirm pins/source copies remain. Test low storage, revoked input/output grants, unsupported image/PDF and part tampering/truncation.
8. Scrub audio/video across part boundaries, including encrypted versions; background playback, saved position, codecs and protected locking. Playback startup is not full-file integrity verification.
9. Configure selected-folder rules, modify a source and compare receipts; revoke the folder grant; remove/re-add the rule; test safety-limit messages, denied notifications and battery restrictions. An unchanged scan is not a new file success.
10. Create a vault, save the recovery key independently, recover it in another test install, reject wrong password/key and inspect remote captions for encrypted names. Lock during crypto/preview/playback; check for remaining plaintext previews, private task names and notification leaks. Test background/app lock and document provider denial.
11. Attach a PDF from TG Drive through another application's document picker, cancel a slow read, and verify read-only behavior/account isolation. Incoming shared files must wait for explicit destination approval, including when the app starts locked.
12. Capture images, apply manual crop/rotation/grayscale, inspect PDF pages, run Latin OCR and correct text. Measure recognition accuracy on known documents. Arabic OCR and automatic edge detection are outside this build.
13. Review cleanup candidates, rehash local sources, change one original after backup and confirm refusal. Use Android's deletion confirmation; cloud copies must remain.
14. With separately configured HTTPS test services, export to WebDAV and compare the downloaded hash; create/password/expire/revoke a public copy; interrupt/retry upload; change settings after enqueue and ensure the original destination remains bound. Never send Telegram sessions to the server.
15. Check API 26 and current Android, 16 KB page-size devices, dark mode, TalkBack, large fonts, long Unicode names and animation responsiveness. Debug-device smoke success does not establish release performance.

Production distribution also requires independent crypto/native dependency review, signing-key backup, current Telegram API terms and Google Play privacy/policy review.

## Local validation evidence (2026-10-24)

- Debug and Release each executed **61 unit/integration tests**, zero failures/errors. The 22 queue/repository integration cases use a fake Telegram transport, not a live account.
- Debug/Release lint: zero errors; 21 warnings (including dependency notices and API/style suggestions). Warnings were not disabled to obtain a pass.
- Debug APK, instrumentation APK, minified unsigned Release APK and unsigned Release AAB built locally with JDK 21/SDK 36. Local unsigned artifacts cannot update the signed workflow application in place.
- Twelve packaged native libraries passed ELF alignment: 16 KB on 64-bit ABIs and 4 KB on 32-bit ABIs. ZIP alignment and actionlint are checked separately.
- Optional sharing server: six tests passed, zero skipped/failures.
- The public 0.1.1 APK signature was verified and its certificate fingerprint was pinned for update continuity. This is not a physical-device upgrade test.
- Local device/account/service acceptance limitations above remain in force. Consult the GitHub Actions run for execution of the new emulator suite and signed artifact generation.

The native device suite also passed on GitHub's accelerated Android 30 emulator in [run 40121518708](https://github.com/jntynho/TG-Drive/actions/runs/40121518708/job/120953907829). This verifies the checked-in smoke scope; it does not establish authenticated account or physical-device acceptance. The older deletion test activates the accessible overflow action; separate native file-card cases exercise physical taps and long press.

The 0.4.0 navigation update adds collection tests for media MIME filtering, current-version upload ordering, nested folders, unfinished uploads, Trash and vault visibility. Compose tests cover the four navigation choices, Home management links and Gallery preview/action separation. Preference editor tests cover immediate feedback, rapid alternating changes, preserved unrelated fields and rollback on storage failure. A repository integration test verifies preferences persist without republishing library item lists. Generated Home/Gallery/settings previews use synthetic fixtures; they do not prove authenticated Telegram behavior or physical-device animation performance.

Local 0.4.0 validation before the saved-tab compatibility case: 72 tests per Debug/Release variant, including the new interaction and preference cases; zero failures/errors. Lint reports no errors (24 warnings, without suppressions). Native instrumentation compilation and actionlint also pass. Release signing, native emulator execution, upgrade retention and publication are verified separately by the matching Actions run.

Saved-tab compatibility also verifies that serialized 0.3.0 tab values restore to Home and the bottom bar exposes only the four new destinations. New state stores stable strings.

## 0.5 checks

New cases cover the 4:5 gallery's accessible labels without visible filenames/menus, the adaptive header/account action, aspect-aware zoom bounds, server cooldown persistence and retry blocking, batched metadata/legacy decoding, metadata retry without content re-upload, bounded preparation, completion callbacks and preserving sibling records when deleting a shared metadata batch. Rendered previews are synthetic fixtures. Physical-device avatar refresh, photo gestures, video codecs/audio focus and live Telegram rate/throughput still require account/device acceptance.

## 0.6 interface checks

The native launch check now accesses accounts through the sign-in action. Compose cases cover header search/edit/clear, the absence of Gallery search/account-avatar actions, the seven visible settings destinations and removal of the three requested routes, gallery tile aspect/selection/long press, and current Home links. Pure cases verify indexed-usage exclusions and folder-picker descendant exclusion. The existing upload/cooldown/journal suite remains required.

AudioArtworkTest uses native Android MediaMetadataRetriever against two original synthetic MP3 assets, with and without an embedded JPEG cover. These fixtures are test-only and contain no external song or personal image. DialogValidationTest checks that an invalid folder name remains in the native dialog and is not submitted. A Robolectric dialog synchronization limitation led to placing this interaction on the native emulator, preserving the assertions. No test signs into a real Telegram account.

Local UI renders use synthetic state. Playback codecs, account avatars, real provider access, private vault gestures and physical-device frame timing still need on-device acceptance. Publishing retains the existing signature, ABI verification and signed-upgrade gate.

0.7 adds embedded-caption/self-reference/overflow validation; queue tests cover one message per eligible upload, recovery without resending, full reconciliation retaining later renames, and legacy batch relocation during deletion. Media sort/date/size tie ordering and time formatting have unit tests. `GalleryGesturesTest` exercises animated double-tap return-to-fit and anchored sort selection without losing the search query on Android. The local build compiles native tests; their execution requires a device/emulator and is performed by the release workflow.

0.8 queue tests hold two preliminary native uploads before any message is sent, pause one without cancelling its sibling, refill a third slot, restore exact hashes and retry without duplicate publication. They also cover simultaneous content duplicates, content-stage 429 deadlines, immutable snapshots, missing/tampered snapshots, old metadata batches and vault restoration. Coordinator tests verify publication remains available while unrelated content is held and retain a full 900-second deadline. Fake native boundaries establish application behavior, not live Telegram throughput.


## 0.9 audit regressions

Added integration cases for an original edited after snapshot preparation (receipt must retain the older revision fingerprint), encrypted pending recovery acknowledgement, sequence-specific notice acknowledgement with an independent share result, partial rule updates preserving a newer toggle, idempotent restored folder drafts and repeat import references. Compose checks cover a real nested setting search target, a background computation held during a privacy-scope change, and server-gated code-recovery actions. Native GalleryGesturesTest also replaces the decoded bitmap while zoomed and invokes the accessible Fit image action. These cases use synthetic sources/transport and do not establish live Telegram rate or physical-device frame performance.


## UI/UX Pro Max review

The local UI corrections are recorded in [the Arabic review](UI-UX-SKILL-REVIEW.ar.md). Debug and Release each pass 128 tests, including 320dp/200% text, 640×360dp landscape authentication, saveable non-secret drafts, locked-folder visibility, buffering audio controls and bounded seek. `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` pass; lint reports no errors and 30 warnings. A full run hit an existing 5-second transfer-test timeout once; isolated transfer tests and a sequential full rerun passed without modifying that test or its assertions. The skill checkout stays outside the repository; no runtime dependency was added. Real TalkBack, system keyboard/biometrics, motion-scale changes and physical-device frame timing remain device checks.

## 0.10 upload audit and combined validation

The [upload audit](UPLOAD-0.10.ar.md) accompanies the UI review above. Debug and Release each pass **141 tests, zero failures/errors/skips**. The 13 added upload cases cover zero artificial success spacing with serialized publication, premium/overflow waits, whole-source and part hashes, event-driven completion, nonblocking acknowledged-upload release, immediate single-message completion, satisfied device gates, mutable multipart sources, corrupted encrypted staging, recovered caption mismatch and durable completion receipts. Ordinary errors containing a wait-like source name cannot invent an account cooldown.

Combined validation uses `--max-workers=1 testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest`. Both lint variants report zero errors and 30 warnings. `actionlint` passes for the updated release workflow. Compilation of device tests does not mean they were executed locally; GitHub retains native device, signed APK and upgrade gates before publication. Fake-wire and virtual-time tests verify pipeline behavior, not real Telegram throughput or immunity to server limits.
