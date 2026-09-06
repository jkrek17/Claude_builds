# Coaching UI: spatial-first, minimal text

The live camera screen shows the photographer *where to move*, not paragraphs about composition.
Words are a label on a spatial cue, never the cue itself. Reasons and sentences belong on the review
screen. This document is the specification the camera screen implements.

## Principles

1. **The photo dominates.** Nothing sits over the centre of the frame except the target ring.
2. **One cue at a time.** Exactly one primary cue is on screen; secondary advice is a row of small
   icons, never sentences.
3. **Physical, not photographic.** Every cue says what to do with the camera (move, raise, tilt, step),
   anchored to the edge of the frame it refers to.
4. **Rotation-safe.** Only glyphs and 1-3 word chips ever rotate. Cues are anchored in *physical* space
   (the physical right edge is whichever screen edge is currently physically right).
5. **Calm.** Cues change only when the smoother changes the headline; motion is a slow breathe, not a
   pulse.

## Elements

### Readiness arc (on the shutter)
- The shutter's outer ring is an arc that sweeps with the smoothed score: 0 at 8 o'clock, full circle
  at 100, clockwise. Colour follows the score tier (white below 70, amber to 87, ready-green at 88+).
- Shoot-ready: full green ring, soft outer glow, one haptic on entry (existing), small "SHOOT" chip
  above the shutter for as long as the state lasts.
- The numeric score is optional (Settings > Show score, default on) and small: a 32 sp tabular numeral
  in a compact chip at the physical-top edge of the preview, no colour animation beyond the tier colour.
- While awaiting a subject the arc is dimmed to 30 % and the chip reads "Looking for a face" (or the
  object/group variant).

### Primary cue
Derived from the headline recommendation's `direction` and category:

| Direction / category | Cue |
|---|---|
| LEFT / RIGHT | Edge chevron: a 64 dp translucent chevron centred on the physical left/right edge of the preview, pointing outward. |
| UP / DOWN | Edge chevron on the physical top/bottom edge. |
| CLOSER | Four corner brackets (18 dp) that ease 8 dp inward and back over 1.4 s. |
| BACK | The same brackets easing outward. |
| ROTATE_* | Bubble level at the physical-top edge: fixed tick marks and a 48 dp line rotated by the smoothed roll; snaps to green when level, fades 1.5 s later. No chevron. |
| No direction (separation, symmetry-centre, looking room without a side) | Chip only, at the physical-top edge. |

- The chevron breathes: alpha 0.55 to 0.8 over 1.2 s, ease-in-out. It never translates.
- The target ring (18 dp, 1.5 dp stroke, 70 % white) stays at the smoothed target while the headline is a
  placement/looking-room/edge cue and the target is outside the dead zone. The old arrow near the
  subject is removed; the chevron carries the direction.
- The region highlight (background collision, edge cuts) stays: 1 dp, 45 % white, rounded, fades in
  and out with the headline.

### Chip (the only live text)
- Attached to the chevron (inside the frame, 12 dp from the edge, centred on the chevron) or, for cues
  without a chevron, centred at the physical-top edge under the score chip.
- 1-3 words, titleMedium, on the standard scrim, max width 55 % of the preview's short side.
- Wording (`GuidanceFormatter.chipText`): "Slightly right", "Slightly left", "Raise", "Lower",
  "Closer", "Step back", "Level", "Clear background", "Give looking room", "Too close to edge",
  "Someone's cut off", "Plainer background", "Center it", "Looking for a face", "SHOOT".
- Crossfade 200 ms on change; the smoother already prevents changes faster than ~1 s.

### Secondary advice
- Up to two 28 dp icon chips in a row under the top bar at the physical-top edge (Balanced: 1,
  Coach: 2, Minimal: none). Icons by category: horizon = level icon, headroom = vertical align, edge
  tension = crop, background = layers, separation = contrast, balance = balance, symmetry = flip.
- Tap: a one-line explanation (the recommendation's `instruction`) replaces the chip for 3 s.

### Quiet states
- No advice and score >= 70: chip fades out; a 20 dp check mark appears beside the shutter for 1 s
  ("hold this framing"), then nothing.
- No scene yet: "Point at a subject" chip at the physical-top edge, fades after 3 s of a stable subject.

## Guidance levels
| Level | Primary cue | Chip | Secondary icons |
|---|---|---|---|
| Minimal | only for severity >= MEDIUM | yes | none |
| Balanced | yes | yes | 1 |
| Coach | yes | yes | 2, tappable explanations |

Reasons ("The scene is strongly symmetric, so...") appear only on the review screen.

## Landscape
- Chevrons and brackets are positioned by *physical* edge via the overlay mapper's rotation.
- The score chip, cue chip and secondary icons are laid out along the physical-top edge with
  `RotatedChrome`; they contain only glyphs and 1-3 words, so rotation never produces a wall of text.
- The shutter arc and bottom bar never move; their icons counter-rotate.

## Accessibility
- The chip text plus direction ("Move slightly right") is the live region announced by TalkBack.
- Chevrons and brackets are decorative (no semantics); the chip carries the meaning.

## Debug
- Developer mode keeps the full metric list and adds the rotation readout
  (`Rot: device=.. upright=.. chrome=.. roll=..`).
