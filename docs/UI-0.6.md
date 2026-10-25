# TG Drive 0.6 — interface refinement

## Requested changes

- Gallery has no search field or search action. Its square-corner 4:5 tiles still hide filenames and overflow menus. Type filters and Uploaded date groups do not pretend to be capture dates.
- The account-avatar action is removed from every shared header. Settings → Telegram accounts opens the account sheet with each available Telegram photo. The sign-in screen retains a body action for account management before the library is available.
- Organize by rules, Resolve conflicts and Optional connections are absent from Settings, search results and restored navigation routes. Existing conflict candidates, metadata, credentials and already configured optional copy destinations are retained; no cloud file is deleted by this UI change.
- Search appears in native route headers for Home, all files, Audio and root Settings. Clear and IME Search actions work in the header. Gallery deliberately receives no search configuration.
- The old Home “Your files” intro is replaced by a compact storage summary: indexed active bytes, visible current-file count, type distribution and offline-copy size. Hidden locked vaults, Trash, previous versions and other Telegram messages are excluded. The ring represents composition by file type, not an invented total/free quota.
- Embedded song artwork is decoded at a bounded size and uploaded using the existing encrypted thumbnail path where applicable. Older items can use available private cache, an independently hash-checked original of at most 32 MiB, or Telegram document thumbnails. The app does not fetch an entire original from Telegram merely to obtain cover art. Missing artwork retains an honest audio placeholder.

## Previously approved refinements included

- Home recent content precedes suggestions; See all enters a real cross-folder recent view. Home's Add action enters the same add sheet as file browsing.
- Gallery supports long-press/explicit selection, selected counts, ZIP export, same-vault moves and Trash. The viewer pages between media with a counter; one-finger paging is left to the pager at fit scale and pan is consumed while zoomed. Immersive controls are shared across pages. Only the active gallery video owns a player.
- Audio has a cover-oriented player and a profile-bound mini-player with open, pause/resume and dismiss actions. File cards preserve available offline/vault indicators and accessible status labels.
- Viewers share the root operation snackbar. Trash offers Undo; batch selection is cleared after successful work. Filename validation keeps invalid input; file action dialogs retain busy/error state. Move pickers filter incompatible vaults and folder descendants.
- Common file actions appear first; less common actions are under More options. Unconfigured external-copy actions are not advertised after the connection settings were removed.
- Settings search, concise transfer labels/summary, parent-aware settings Back motion, bounded thumbnail loading/retry states, wrapping indicators/actions, and improved contrast for accent text and blue button labels.
- Existing upload batching, durable account cooldowns, staging limits, content verification and signing identity are unchanged.

## Limits and verification

No album-art lookup service or external music API is used; covers must exist in the file or an available corresponding preview. Native cover extraction and invalid-name interaction have dedicated emulator cases. Unit/integration, lint, native device checks, minified packaging, signatures and upgrade retention remain separate checks. Synthetic screenshots do not establish live Telegram or physical-device performance.

Optional future ideas from the earlier review—such as a large-library date jump picker and richer artist/album metadata—are not represented as finished features. There is no invented cloud quota, no automatic deletion of conflict records and no new backend service.
