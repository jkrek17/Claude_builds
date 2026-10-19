# Composition Coach: app UX specification

Goal: a camera you can hand to anyone. They point, they see one cue, they follow it, the shutter
turns green, they shoot. Nothing else demands attention. `docs/COACHING_UI.md` specifies the coaching
layer; this document covers the rest of the app.

## Principles

- **One screen, one job.** The camera is the app. Every other screen is a short detour that returns
  to the camera.
- **Conventions over invention.** Layout, gestures and icons match what people already know from the
  Pixel and iPhone cameras: gallery left, shutter centre, flip right, modes above the shutter, flash
  and settings top.
- **Progressive disclosure.** The four settings people actually change are visible; the rest is behind
  "More".
- **Words are labels.** Live text is 1-3 words. Sentences appear only on the review screen.
- **Calm by default.** 200 ms ease-out for state changes, 120 ms for presses, no loops except the
  chevron breathe, and no motion at all when the system animation scale is 0.

## Information architecture

```
Camera (home)
├─ Review (after every capture; Keep / Retake / Share; back = Keep)
├─ Settings (one screen, four primary controls + "More")
│  ├─ More (detection toggles, battery saver, developer mode)
│  └─ About / Privacy
└─ Permission (only until camera access is granted)
```

## Camera screen

Zones, top to bottom, on a black ground:

1. **Top strip** over the preview (translucent): flash (left), settings (right). Nothing else.
2. **Preview** 4:3. The coaching layer per `COACHING_UI.md`: score chip at the physical top, edge
   chevrons/brackets/level, target ring, region highlight, cue chip, secondary icon row.
3. **Mode strip** directly above the shutter: `Auto · Portrait · Group · Landscape · Architecture · Object`.
   Selected mode is centred, white and slightly larger; others 60 % white. Tap or horizontal swipe on
   the strip changes mode; a haptic tick confirms. This is the same setting as "Shooting mode" and the
   two stay in sync. (The setting stays in Settings for discoverability, but the strip is the primary
   control.)
4. **Bottom bar**: gallery thumbnail (left, 44 dp rounded square, last photo), shutter (centre, 72 dp,
   readiness arc), flip camera (right).

Interactions: tap preview = focus ring + AE; pinch = zoom with a "1.0×" chip while pinching;
volume-down = shutter; long-press shutter is reserved (no action for now).

## Settings screen

Primary (always visible, in this order):

| Control | Type | Default |
|---|---|---|
| Shooting mode | segmented / chips | Auto |
| Guidance | Minimal · Balanced · Coach segmented | Balanced |
| Show score | switch | on |
| Grid | switch | off |

"More" (collapsed by default):

| Control | Type | Default |
|---|---|---|
| Battery saver | switch, subtitle "Slower analysis, subject mask off" | off |
| Detect objects | switch | on |
| Subject mask | switch (disabled + explained when battery saver is on) | on |
| Developer mode | switch | off |

Footer: "All analysis runs on your device." with links to Privacy and About (version).
Every row 56 dp tall, 48 dp targets, one-line subtitles only where the label is not self-explanatory.

## Review screen

- Photo fills the 4:3 area, letterboxed on black, any orientation.
- Card beneath: the shutter arc reused as a 56 dp score ring with the number inside; the mode chip;
  two green-dot strengths and two amber-dot improvements as plain sentences (this is where reasons
  live); actions row: Retake (outlined), Share (icon), Keep (filled).
- Back gesture and the top-left arrow both mean Keep.

## First run

1. Permission screen: camera glyph, one sentence ("Composition Coach needs the camera to coach your
   framing. Nothing leaves your phone."), "Allow camera" filled button; when permanently denied,
   "Open settings" text button.
2. A single dismissible card over the first camera session: "Point · Follow the cue · Shoot when green"
   with a "Got it" button. Never shown again.

## Visual language

- Ground: black. Chrome: white on a 40 % black scrim with 16 dp radius. Accents: ready green and warn
  amber only. No other colour.
- Type: system sans; score numerals tabular. Sizes: chip titleMedium, secondary bodySmall, score 32 sp.
- Icons: Material Symbols outlined, 24 dp, 1.5 dp stroke look; all interactive targets >= 48 dp.
- Spacing on an 8 dp grid; 12 dp inset from preview edges for anchored cues.

## Feedback

- Haptics: light tick on mode change and shutter press, single medium tick on entering shoot-ready.
- No sounds.
- Errors: camera unavailable = full-preview message with Retry; capture failed = snackbar; analysis
  unavailable = small "Analysis unavailable" chip, camera still works.

## Accessibility

- Every interactive element has a content description; the cue chip is a polite live region; the
  score chip announces "Composition score 82".
- Text on scrims meets 4.5:1. Chevrons and brackets are decorative.
- Respect reduced motion: when the system animation scale is 0, no breathe, no arc animation, instant
  crossfades.
