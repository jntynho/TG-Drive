# TG Drive UI audit — before the 0.5 implementation

Scope: all native UI sources, shared components, MainActivity forms, playback service, transfer states and existing synthetic renders. This is a source/design audit, not a claim that every physical device, codec, provider or accessibility configuration has been tested.

| Finding | User impact | Planned resolution |
|---|---|---|
| Static TG Drive toolbar, refresh and text account name | Current location is unclear; toolbar is crowded | One route-aware title, circular Telegram avatar, sync in library actions |
| Home detail and Settings add a second header | Duplicate hierarchy and wasted vertical space | Shared route header with contextual Back and account access |
| Gallery adds its title again | Less space for media | Single title and compact search/count |
| Rounded square cards, filenames and overflow buttons on every gallery tile | Visual noise; weak resemblance to a gallery | Square-corner 4:5 media tiles; accessible labels; actions inside viewer |
| Gallery overflow is a second target beside preview | Easy to hit the wrong control | One primary tile target; action menu in viewer |
| Image transform clamps scale but not pan | Image can be dragged out of view | Fit-aware pan bounds, pinch/pan, focal double-tap reset/zoom |
| Image view has no immersive mode | Chrome reduces photo area | Tap to show/hide chrome; immersive window bars with cleanup |
| Photo preview decodes the image with mutable error state from IO | Stale loading/error presentation | Version-keyed load result, cancellation-safe decode and retry |
| Media controller connects independently of selected file | Old media may appear; extra Play step | Selected media identity binding before autoplay/control display |
| Video fixed to 16:9 with explanatory paragraphs | Portrait clips waste space; player feels like diagnostics | Full available viewport, aspect fit, native controls and error/retry |
| Preview has no full file menu | Gallery actions would be lost after removing tile menus | Preserve full action sheet inside viewer |
| Back labels always say files/settings | Incorrect context for gallery/audio/details | Generic accessible Back, shared header state |
| Multiple top-level route keys were serialized enums | Restore risk after renaming tabs | Legacy-safe restore, stable string saver |
| Asynchronously filtered media retains the prior list briefly | Removed/locked items can remain during recomputation | Immediate visibility guard before rendering |
| Settings privacy → vault Back returns to root | Navigation loses parent context | Explicit parent route mapping |
| Action sheets use large separate rows with repeated title hierarchy | Excessive scrolling | Group primary, organization, copy and destructive actions |
| Some horizontal action rows cannot wrap at large fonts | Clipped controls | FlowRow or horizontal scrolling with correct sizing |
| Some consent checkbox labels are separate touch targets | Hard to select; weak spoken labels | Labelled single checkbox row |
| Dialog text and forms have inconsistent scrolling | Content inaccessible with keyboard/large text | Scrollable constrained form/content containers |
| Native Material icon silhouettes/weights differ by feature | Inconsistent visual identity | Curated Lucide rounded-stroke vector set with license |
| Launcher uses a generic monochrome cloud | Weak recognizable identity | Original TG Drive SVG + Android adaptive launcher assets |
| Transfer state surfaces enum-like strings and raw server errors | A successful content upload may look like total failure | Human stage labels, metadata-pending state, countdown/retry context |
| Per-task flood waits allow other queued sends | Repeated errors across a batch | Persistent account send cooldown and shared sender pacing |
| Metadata and cloud thumbnail messages multiply send volume | Longer gaps and earlier throttling | Bounded batched journal transport; preserve encrypted thumbnail safety |
| Every completed task walks cache | Gaps grow with local content | Throttled maintenance and immediate low-space protection |

Design rules: one page title, clear Back/Close, 48 dp minimum controls, restrained blue emphasis, neutral surfaces, Lucide 1.8–2 dp rounded strokes, consistent 16 dp content margins, 4:5 gallery media without decorative cards, immersive viewers, short horizontal navigation. Keep destructive/export/encryption explanations at the decision that needs them. Do not replace platform pickers or device authentication with imitations.

Device acceptance still needed: actual Telegram avatar refresh, authenticated rate limiting, low-memory photos, hardware video codecs/HDR, Android 26 and current OS, large fonts/TalkBack, window insets and physical-device frame pacing. Implementation and automated evidence are recorded in TESTING.md; this audit is not a promise of undiscovered-bug absence.
