# Native Android visual language — 0.4.0

The five Muse Android screenshots supplied by the project owner are the primary
visual reference: welcome/sign-in, Settings, help (switch off/on), and feedback.
They establish the black sign-in canvas, charcoal inner pages, grey grouped
surfaces, thin inset separators, circular navigation controls, bold sans-serif
labels, filled fields, pill buttons, and blue active controls.

Earlier research included the [independent Muse web teardown](https://github.com/bravew/Neumar/blob/main/dev-doc/plan/2026-09-30-simple-ux-muse/01-research.md)
and [Meta's Gadget SDK](https://github.com/facebookincubator/muse-gadget-sdk).
Neither is a specification of the Android app. Google Play and Muse's site
returned proxy HTTP 403 in this environment; the supplied Android images take
precedence over web descriptions. Static images do not establish motion timing
or the exact proprietary font. This implementation uses Android's sans-serif
font and short horizontal Compose slides, rather than claiming a pixel-identical clone.

## Shared primitives

- `DriveTheme.kt`: dark palette independent of the system wallpaper/theme;
  canvas `#1E1E1E`, sign-in `#050505`, cards `#333536`, blue `#0085FF`.
  Muted text remains light enough to read. No dynamic Material wallpaper colors.
- `DriveComponents.kt`: 24 dp cards, 16 dp page margins, filled rounded text
  fields with a blue focus outline, 52 dp minimum primary buttons, 48 dp circular
  controls, neutral secondary buttons, pill filters and grey/blue switches.
- `SettingsHome.kt`: four grouped cards containing all nine existing sections.
  Detail pages have a centered title and circular Back; the root has Close.
  Settings hides the app toolbar, keeps all four navigation choices available, and returns to
  Home on Close. Back on a detail returns to the settings index.
- `SettingsPage.kt`: related actions/toggles share cards with inset dividers.
  A setting's entire row is one labelled switch target for touch and TalkBack.
- The same palette/primitives cover Files, Activity, Backups, vaults,
  connections, account sheets, filters, file actions, dialogs, previews, app
  lock, and scanner/backup/cleanup forms. Native Android pickers, biometric
  prompts, and media playback controls retain their platform/library behavior.

Page navigation uses 280 ms horizontal slides with FastOutSlowIn easing, reversing on Back and following tab order. Compose observes Android's
animation duration scale. The shell stays outside tab content transitions, and
each tab has a saveable state scope to retain folders, filters and scroll state.
Passwords retain password transformations; Telegram sign-in states, warnings,
destructive confirmations and optional-server requirements remain available.

No Meta branding, avatar, logo, bundled font, image asset or runtime dependency
was added. TG Drive retains its identity. Test-only Compose dependencies do not
enter the shipped APK. ABI downloads and the signing identity are unchanged.

## Review and checks

`DriveDesignTest` runs offline UI interactions under Robolectric/native graphics:
all nine settings destinations remain reachable through scrolling, setting rows
expose one labelled toggle each, and phone sign-in keeps Continue disabled until
input and forwards the number unchanged. Rendered previews are generated in
`app/build/design-previews/` and included in CI's `validation-reports` artifact.
These previews use synthetic state and do not establish authenticated Telegram
behavior or physical-device animation performance. The existing native emulator
suite and signed-upgrade check remain separate release gates.

Historical 0.3.0 validation on 2026-10-25: Debug and Release each passed 64 tests, including
the three new UI cases. Debug/Release lint reported zero errors and 23 warnings
(dependency updates, existing API/style suggestions and two superseded welcome
strings). No lint issue was suppressed. Generated sign-in, settings and privacy
previews were inspected. Device tests, signing and upgrade results are reported
by the corresponding GitHub Actions run, not inferred from these local checks.

## Home, Gallery, Audio and responsive switches

The bottom bar contains Home, Gallery, Audio and Settings. Home retains “Your files” and “Everything you keep, in one place”, shows the latest 20 current content versions across folders, and offers upload, all-files browsing, Activity and Backups. Contextual suggestions link to backup setup or transfer review; they do not apply changes automatically. Folders, Trash, favorites, offline copies and existing management actions remain in Browse all files.

Gallery uses a lazy adaptive grid of square thumbnail tiles, newest first, with video markers and separate action buttons. Audio lists only audio MIME types and opens the existing player preview. Search applies inside each media collection. Pending uploads, Trash and locked vaults are excluded. Upload thumbnails are bounded and decrypted through the existing vault checks. Older imported documents may use Telegram's small thumbnail; files without a preview show a file-type placeholder. Scrolling never downloads an entire original just for a thumbnail.

Switches follow the two supplied close-up references: 64 × 28 dp pill track, 40 × 24 dp capsule thumb, blue/white enabled and gray/black disabled. A 48 dp touch height and a single labelled row toggle preserve accessibility. Color and position animate over 140 ms. Preference edits update the UI immediately, save sequentially and revert to saved values on failure. Saving a preference no longer re-reads the metadata library or traverses the cache; only an actual cache-budget change triggers trimming. Device credential approval still controls App lock.

## 0.5 refinement

See UI-AUDIT.md and UI-REVIEW.ar.md for the pre-implementation audit. A shared route-aware header replaces the branded toolbar; an accessible circular Telegram avatar opens accounts. Gallery tiles use square corners and a 4:5 aspect ratio with no visible filename or menu. The viewer preserves the file action menu, offers immersive pinch/double-tap image zoom with bounded pan, and gives each gallery video its own lifecycle-bound ExoPlayer. Audio uses the existing background service. Lucide vectors are vendored with their licenses; the old extended Material icon dependency is removed. Original SVG and Android launcher assets live in docs/brand and res.
