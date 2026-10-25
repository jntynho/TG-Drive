# Aero Fold app icon

Original TG Drive artwork approved by the user: a white cloud with a softly folded blue corner. The logo and background are independent vector layers. No embedded raster image, external asset, Apple logo or Telegram logo is used.

- `tg-drive-icon.svg`: 512 × 512 presentation icon, with editable `background` and `logo` groups. The cloud occupies about 72% of the presentation width.
- `tg-drive-mark.svg`: the same cloud on a transparent artboard.
- `tg-drive-background.svg`: full square blue gradient, without rounded corners. The launcher supplies its own mask.
- `launcher-preview.png`: circle, rounded-square, squircle and monochrome previews rendered from the generated Android vector geometry, using the central 72dp visible viewport.

## Android sizing

The SVG presentation icon is **not** pasted into the adaptive foreground. Android background and foreground each use a 108dp artboard. The foreground cloud is centered at 54dp and measures 52dp wide (about 72% of the usual 72dp visible mask). Its enclosing rectangle fits inside the 66dp safe circle. This leaves space for launcher masking and motion without enlarging or clipping the cloud. The background fills the entire layer, without a baked-in rounded shape.

Android 8+ uses independent background/foreground resources. Android 13+ additionally uses one uniform cloud silhouette for system-tinted icons; the color fold is omitted from that silhouette. A combined vector fallback is retained. The application's existing `icon` and `roundIcon` entries both resolve to these resources. Native gradients and a clip path preserve the fold; there is no raster foreground or filter shadow. Launcher-provided shadows, animation, normalization and masks can vary by device.

Regenerate the four Android vectors after editing the SVG source:

```sh
python3 scripts/generate_launcher.py
python3 scripts/generate_launcher.py --check
```

The generator requires only Python's standard library. Android vector gradients are supported by the app's minimum SDK (26). Interface glyphs remain the licensed Lucide resources under `third_party/lucide`; the small notification silhouette remains unchanged.
