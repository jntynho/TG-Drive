# Cloud Stack app icon

Original TG Drive artwork: a cloud with a negative-space upward arrow and two storage layers on a blue gradient. No Apple or Telegram trademark artwork is used. A generated raster concept was a reference; all deliverables are separately authored vector paths, with no embedded raster or external resource.

- `tg-drive-icon.svg`: 1024 px full icon, separately editable `background` and `logo` groups.
- `tg-drive-mark.svg`: transparent white mark.
- Android adaptive resources use the same geometry on independent background/foreground layers, with mask-safe margins. Android 13 monochrome has one uniform fill. The existing notification silhouette remains simple for small status icons.

SVG scales without quality loss. Android's launcher supplies mask and motion; applications cannot force every launcher to animate or size icons identically. UI glyphs use licensed Lucide resources under `third_party/lucide`.
