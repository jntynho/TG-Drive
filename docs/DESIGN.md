# Native Android visual language — 0.3.0

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
font and short Compose fades, rather than claiming a pixel-identical clone.

## Shared primitives

- `DriveTheme.kt`: dark palette independent of the system wallpaper/theme;
  canvas `#1E1E1E`, sign-in `#050505`, cards `#333536`, blue `#0085FF`.
  Muted text remains light enough to read. No dynamic Material wallpaper colors.
- `DriveComponents.kt`: 24 dp cards, 16 dp page margins, filled rounded text
  fields with a blue focus outline, 52 dp minimum primary buttons, 48 dp circular
  controls, neutral secondary buttons, pill filters and grey/blue switches.
- `SettingsHome.kt`: four grouped cards containing all nine existing sections.
  Detail pages have a centered title and circular Back; the root has Close.
  Settings temporarily hides the app toolbar/bottom navigation and returns to
  Files on Close. Back on a detail returns to the settings index.
- `SettingsPage.kt`: related actions/toggles share cards with inset dividers.
  A setting's entire row is one labelled switch target for touch and TalkBack.
- The same palette/primitives cover Files, Activity, Backups, vaults,
  connections, account sheets, filters, file actions, dialogs, previews, app
  lock, and scanner/backup/cleanup forms. Native Android pickers, biometric
  prompts, and media playback controls retain their platform/library behavior.

Navigation fades use 180 ms entry and 100 ms exit. Compose observes Android's
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

Local validation on 2026-10-25: Debug and Release each passed 64 tests, including
the three new UI cases. Debug/Release lint reported zero errors and 23 warnings
(dependency updates, existing API/style suggestions and two superseded welcome
strings). No lint issue was suppressed. Generated sign-in, settings and privacy
previews were inspected. Device tests, signing and upgrade results are reported
by the corresponding GitHub Actions run, not inferred from these local checks.
