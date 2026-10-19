# Product definition

**For people who love photos but never learned photography.** The app takes the photo you meant to
take, then teaches you afterwards, in your own pictures, and learns what you like.

Name: JustRight (see docs/BRAND.md); formerly Composition Coach. The promise in one line: *the camera
that frames for you, and shows you why afterwards.*

## Two experiences, one shutter

| | Easy (default) | Coach (opt-in) |
|---|---|---|
| Live cue | one chevron / brackets / level with a 1-3 word chip | same, plus one secondary icon row |
| Score | shutter ring only, no number | ring plus small number |
| Shutter | tap = capture at the best moment within 4 s (assist on) | same, hold for instant capture |
| Auto fixes | level, sharpest frame, enhancement: on | on |
| Explanations | none live | one-line reason under the chip |
| After the shot | full after-action card (below) | same |

Easy mode never says "rule of thirds". Coach mode may. Both get the same after-action coaching.

## Live: the fewest words that get the shot

Unchanged from `COACHING_UI.md`, with these defaults:

- Score number off, chip on, secondary icons off (Easy).
- Shoot-at-peak on: tap arms the auto-shutter; it fires when framing is ready and the hand is steady, or
  after 4 s. Hold the shutter to shoot instantly.
- Auto-level, sharpest-of-burst and the best available enhancement on.
- **Outcomes, not modes.** The strip above the shutter lists the photograph you want, in plain words.
  Picking one sets everything underneath it: scene rules, capture pipeline, stacking, post-processing,
  and the single instruction it needs. The user never meets a setting.

| Outcome | What the app does underneath | The one thing it asks of you |
|---|---|---|
| **Anything** | Auto scene detection, best available enhancement, sharpest-of-burst, auto-level | nothing |
| **Portrait** | face coaching (light, headroom, looking room); vendor Bokeh extension when the phone offers it, else our mask-based background blur on review | "Turn so the light is on their face" when needed |
| **Group** | everyone in frame and eyes open before it fires; edge cuts are blocking | "Someone's cut off" / "Eyes closed" |
| **Landscape** | horizon level and placement; HDR when available; focus stack (3 focus distances merged by local sharpness) so near and far are sharp | "Level" / "Hold still" |
| **Night** | vendor Night extension; longer steady hold with a progress ring | "Hold still" |
| **Light trails** | Motion stack, lighten blend, 3-4 s, gyro-aligned | "Hold still for 4 seconds" |
| **Silky water** | Motion stack, average blend, 2-3 s | "Hold still for 3 seconds" |
| **Clear the crowd** | Motion stack, median blend, 4-6 s; coach flags moving people first | "Hold still for 5 seconds" |
| **Food & things** | object subject, top-down level (pitch) and shadow/light coaching, tighter crops suggested | "Tilt flatter" / "Closer" |
| **Selfie** | front camera; mirror-aware cues, arm's-length framing, front-light coaching, countdown | "Chin down a touch" / "Arm out" |

**Timer + Group.** Any outcome can be put on a 3 or 10 s timer; in Group the timer hands over to
readiness: prop the phone, join the photo, and it fires when everyone is in frame with eyes open.

**Photos only.** This is a stills camera by decision; there is no video mode, and the brand says so.

Outcomes that need a hold show the live accumulation in the preview with a progress ring and abort
cleanly on heavy motion ("Too shaky, try again"). Every outcome saves a normal first frame alongside
any stacked or processed result, so an experiment never costs the shot.

## After the shot: where the coaching lives

The review screen becomes an **after-action card**, the heart of the product. It is readable in five
seconds and rewards a second look.

1. **Score ring** with the number, and a one-word verdict: *Keeper · Good · Try again*.
2. **What worked** (up to three, green): plain sentences from the engine's strengths, e.g. "Eyes on the
   upper third", "Nice light on the face", "Level horizon".
3. **Try next time** (one, amber): the single physical change with the biggest predicted gain, e.g.
   "A step to the left would have cleared the lamp post (+9)". Comes from the optimizer's best candidate
   and the top improvement; always one, never a list.
4. **Better crop?** (when available): up to three crop cards with predicted scores; tap to preview, save
   as a copy. Original untouched.
5. **What if** (when available): press-and-hold to see the optimizer's framing over your photo with
   "72 → 83".
6. **Details** (collapsed): capture diagnostics, every metric, the mode and recipe. For the curious and
   for Pro.
7. **Keep / Retake / Share.** Keep is the default and the back gesture. A swipe dismisses the card
   instantly (= Keep). Setting: show the card *always* (default) or *only when there is something to
   say* (a Try-next-time or a crop).
8. **"Not helpful"**: one tap on a Try-next-time or a cue mention when the advice was wrong. Feeds the
   taste model the other way and is the only quality signal we collect; nothing leaves the phone.

Keep and Retake are also the learning signal (below).

## Learning what you like

All on-device, nothing leaves the phone, resettable in one tap.

- **Signals**: keep vs retake; which live cue was showing at capture (followed or ignored); crop choices
  (which aspect, how tight); favourites marked in the app; time spent on the after-action card.
- **Taste model** (`AdaptiveWeights`, Wave 2): per-category multipliers nudged by the signals, bounded
  and slowly decaying, applied to scoring and to which cue becomes the headline. A photographer who
  keeps centred portraits stops being told to move off-centre.
- **Your eye** screen (from the gallery thumbnail long-press or Settings): a quiet progress view.
  "This month: 38 keepers, level horizon 9 in 10 times, your light has improved." Three tendencies in
  plain words ("You like tight, centred portraits"), the advice you follow most and least, and your
  best three shots with their cards. No streaks, no badges, no gamification.
- **Coach-level nudge**: after 20 keepers, offer Coach mode once ("Want a little more guidance live?").

## Hidden under Pro

Exposure slider, zoom presets, enhancement selector, grid, guidance level, detection toggles,
developer mode. One switch in Settings reveals them. Easy users never see them.

## Principles (ordered)

1. The photo first. No overlay ever covers the subject.
2. One cue live. Many words after.
3. Fix what can be fixed silently; coach only what needs a human.
4. Teach from the user's own photos, never from theory.
5. Learn the user's taste; never argue with it.
6. Nothing leaves the device.

## Launch hygiene

- Shutter sound where the law requires it (regions where the device reports it mandatory), via
  `MediaActionSound`; otherwise silent.
- Open-source licences screen (CameraX, ML Kit, Compose, Coil) under About.
- Every user-facing string in resources; the chip wording table lives in `strings.xml` so the app can be
  translated. English only at launch.
- Graceful degradation per device: when an outcome's pipeline is unavailable ("Night isn't available
  on this phone"), the outcome still works with the best fallback and says so once.
- Beta feedback: "Send feedback" in About composes a share with the last after-action card and capture
  diagnostics as text, through the system share sheet. No analytics, no network.

## Success looks like

- A first-time user takes a keeper within a minute without reading anything.
- Users open the after-action card on most shots and read it for a few seconds.
- The share of "Try again" verdicts falls over a user's first few weeks.
- Retakes after following "Try next time" score higher than the original.
