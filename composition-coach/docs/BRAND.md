# SceneRight: brand book

The app formerly called Composition Coach. This is the reference for the name, the story, the voice,
the in-app copy and the identity. Naming rationale, collision checks and the final decision:
`brand/NAMING.md`. How to adopt the name in the repo: `brand/RENAME.md`. Assets: `brand/identity/`,
`brand/site/`; the designer's guide: `brand/guidelines/brand-guide.md`. In-app strings live in `app/src/main/res/values/strings.xml`.

- **Name:** SceneRight (one word, capital S and capital R in prose and as the launcher label; the
  marketing wordmark is set lowercase "sceneright" with the small descriptor "photo").
- **Store title:** SceneRight: Camera Coach ("scene" is an everyday word, so the store title
  carries a descriptor).
- **Header line:** A little guidance. More keepers.
- **Tagline:** The photo you meant to take.
- **Support line:** Frames for you. Shows you why.
- **One sentence:** SceneRight is a camera that frames the shot for you, takes it at the best moment,
  and then shows you, in your own photo, what worked and one thing to try next time, all on your phone.

## Brand story

You see something worth keeping: your daughter laughing, the light on the hills, a plate you're proud
of. You raise your phone, and somehow the photo never looks like the moment did. A little too far
away. A little crooked. Too much sky.

SceneRight is about the moment the scene comes right. Point the camera and one quiet cue shows you
where to move. Step, tilt, and then there is a moment when the frame settles and the scene in front of
you is the one you saw. Tap once, and SceneRight waits for exactly that moment, then takes the
picture. It levels the horizon and keeps the sharpest frame without asking.

Afterwards it shows you why the photo works, in plain words, and one thing to try next time. When it's
right, it says so in one word: **Keeper**.

Over time it learns what you keep and what you skip, so the guidance fits your taste, not a rulebook.
And all of it happens on your phone. Nothing leaves it.

*(173 words.)*

## Positioning statement

For people who love taking photos but never learned photography, **SceneRight** is the camera that
frames the shot for you and then shows you, in your own pictures, what worked and what to try next.
Unlike filter apps, which change the photo, and pro camera apps, which hand you more controls,
SceneRight changes the photographer, gently: just enough guidance, from your own shots, tuned to your
taste, all on your phone.

## Who it is for

**Maya, 36, nurse, two kids.** Eleven thousand photos on her phone and maybe forty she would print.
She shoots between other things, one-handed, and the birthday photo always has a lamp growing out of
someone's head. She will never watch a photography tutorial. She wants the camera to get it right,
and she will read two lines afterwards if they are about *her* photo. Success for Maya: more keepers,
no extra steps.

**Tom, 63, retired engineer, walks a lot.** He had a film camera once and still has the vague
feeling there are rules he never learned. He is curious, reads every word on a screen, and likes to
understand why. He distrusts apps that want an account and reads the permissions before installing.
Success for Tom: he notices his horizons are level now, and the "Your eye" screen tells him so.

## The three truths we can prove

Every claim in marketing traces to one of these. Each has its proof and its status today.

| Truth | What we say | How we prove it | Status |
|---|---|---|---|
| **It stays on your phone.** | "Everything happens on your phone. Nothing is uploaded." | The app has no internet permission; a build check (`verifyNoInternetPermission`) fails the build if one ever appears. No account, no analytics, no ads. Android backup is off (`android:allowBackup="false"` plus `dataExtractionRules`), so the photo journal and taste model are not copied to a cloud backup. Demo: it works the same in airplane mode. | **Shipped.** One caveat below. |
| **It shoots at the best moment.** | "Tap once. It takes the photo when everything lines up." | Tap arms the shutter; it fires when the framing is ready and the phone is steady, or after 4 seconds; hold to shoot at once. Demo: side by side with a plain tap. | **Shipped** in Wave 2 (shoot-at-peak). |
| **It learns your taste.** | "It learns what you like." | Keeps, retakes and the cues you follow or skip nudge bounded preferences, on the phone. The "Your eye" screen shows what it learned in plain words; one tap resets it. | **Shipped** in Wave 2 (the taste model, `AdaptiveWeights`, `PhotoJournal`, and "Your eye"). |

Caveat on the first truth (the claim stands; this is for the FAQ):

- **Face model download.** On first launch Google Play services downloads the face-detection model.
  That is Play services fetching a model, not the app sending anything, and the privacy policy already
  explains it. Marketing copy needs no change; the FAQ answer is in the privacy policy.

## What we never say

| Never | Because | Say instead |
|---|---|---|
| AI-powered, AI camera, smart, intelligent, algorithm | It is a claim about us, not about your photo, and the category is full of it | "It sees where to move." "It learns what you like." |
| Pro, professional, like a pro, DSLR-quality | Our people are not pros and do not want to be told to become one | "The photo you meant to take." |
| Rule of thirds, composition, leading lines, negative space (in marketing) | Jargon. Easy mode never says it; Coach mode may, inside the app only | "Framing", "where to stand", "eyes a little higher" |
| Perfect shot, flawless, magic | Untrue and unearnable. "Right" is the ceiling, and it is enough | "A keeper." |
| Score as a judgement ("your photo scored 54") | The number is a tool, the verdict is the message | "Try again." "Good." "Keeper." |
| Wrong, bad, mistake, fix your photo | We never judge the person or argue with their taste | "Try next time: ..." |
| Streak, badge, level up, unlock, achievement | No gamification; this is a camera | "This month: 38 keepers." |
| Content, followers, likes, viral, influencer | Not a social app | "Photos", "your pictures" |
| Cloud, sync, account | We have none, and the words make people wonder | "On your phone." |
| Revolutionary, game-changing, the best camera app | Never compare up, never shout | Show the card, let it speak |
| Puns on the name ("set the scene", "get the scene right!") | The name already says it; repeating it as a slogan cheapens it | Let the moment say it: "Keeper." |

## Voice and tone

**One idea:** a calm friend with a good eye, standing next to you, who says just enough and only when
it helps.

1. **Live, the fewest words.** One cue, 1-3 words, a physical action: *Slightly right. Closer.
   Level.* No sentences, no reasons, no punctuation.
2. **After, plain sentences.** Short, concrete, about this photo. A subject you can see and an action
   you can do with your feet or your hands: *Try next time: move a little closer.*
3. **Physical, not photographic.** Step, raise, lower, closer, turn. Not "reposition the subject".
4. **Credit the person.** "You kept 38." Not "SceneRight took 38."
5. **One thing at a time.** Always one "Try next time", never a list.
6. **The camera never says "I".** SceneRight is named in the third person when needed; "we" only on
   the website and in the store listing.
7. **Warm, not cute.** No exclamation marks in the app. No puns. No emoji.
8. **Never argue with taste.** When someone keeps ignoring a cue, the app mentions it less; it does not
   explain why they are wrong.

Marketing lines from the identity board, for reuse as they are: *A little guidance. More keepers.* ·
*One quiet cue. Everything settles.* · *Calm in the hand. Clear at a glance.*

### Twelve before and after examples

Live (chip on the preview, 1-3 words, matches `GuidanceFormatter.chipText`):

| # | Before | After |
|---|---|---|
| 1 | Move slightly right → (subject placement 0.42) | Slightly right |
| 2 | Rotate slightly clockwise to level the horizon | Level |
| 3 | Lower camera slightly: excessive headroom | Lower |
| 4 | Leave more room in front of subject | Give looking room |
| 5 | Background object intersects subject | Clear background |
| 6 | Great framing! 94 — SHOOT | SHOOT *(and the ring turns green; nothing else)* |

After the shot (the card, plain sentences):

| # | Before | After |
|---|---|---|
| 7 | Strong: subject placement score 0.91 | Her eyes are high in the frame. That's what makes it. |
| 8 | Could improve: headroom 31 % (target 8-15 %) | There's a lot of sky above him. Tilt down a touch next time. |
| 9 | Composition score: 54/100. Try again. | **Try again.** One step to the left would clear the lamp post. |
| 10 | Edge tension detected at right border | Her elbow touches the edge. A little more room on the right next time. |
| 11 | AI-powered on-device composition analysis | Checked on your phone. Nothing uploaded. |
| 12 | Pro tip: apply the rule of thirds! | Try putting the horizon a little lower next time. |

## In-app copy

The source of truth is `app/src/main/res/values/strings.xml`; the lines below are the intent behind
it. The launcher label (`app_name`) is **SceneRight**.

### Permission screen

- Title: **SceneRight needs your camera**
- Body: It looks through the lens to help you frame. Everything stays on your phone. Nothing is
  uploaded.
- Button: **Allow camera**
- Permanently denied: Camera access is off. Turn it on in Settings to start. Button: **Open settings**

### Onboarding (one card, first camera session, never again)

- Title: **Point. Tap once.**
- Body: Follow the one cue on screen. Tap the shutter and SceneRight takes the photo when everything
  lines up. Hold to shoot right away.
- Line under it: After each photo, see what worked and one thing to try next time.
- Button: **Start**

`strings.xml` still carries the earlier one-line card (*Point · Follow the cue · Shoot when it turns
green* / **Got it**); with shoot-at-peak shipped, the card above is the one to move to.

### Verdict words

**Keeper · Good · Try again.** Capitalised as shown, no punctuation. On the card the verdict sits
beside the score ring and nothing else does; the ring keeps the live arc's tier colours.

- Keeper: the photo you meant to take. The scene, right.
- Good: worth keeping; the card has one idea for next time.
- Try again: said kindly, never "bad". The card still leads with what worked.

### After-the-shot card labels

What worked · Try next time · Better crop? · What if · Details · Keep · Retake · Share.
Crop saved: *Saved as a copy. Your original is untouched.* Nothing to suggest: *Nothing to change.
Keep this one.*

### "Your eye" screen

- Title: **Your eye**
- Period: This month
- Lines (examples): **38 keepers** · Level horizon 9 in 10 times · Your light has improved
- Section: **What you like**: You like tight, centred portraits. You like plenty of sky in a view.
  You shoot food close and from above.
- Section: **Advice you take most**: Closer.
- Section: **Advice you skip most**: Give looking room. *That's fine. SceneRight will mention it less.*
- Section: **Your best three** (each opens its card)
- Footer: Learned on your phone. Never uploaded. · **Start fresh**
- Start fresh dialog: **Start fresh?** SceneRight forgets what it has learned about your taste. Your
  photos stay where they are. [Start fresh] [Cancel]
- Empty: Keep a few photos and this fills in.

No streaks, no badges, no "level 3 photographer".

### Coach nudge (once, after 20 keepers)

**Want a little more guidance live?** Coach mode adds a short reason under each cue. You can switch
back any time. [Try Coach mode] [Not now]

### Ten "Try next time" sentences

One physical change, one sentence, the predicted gain in brackets when the engine has one.

1. A step to the left would have cleared the lamp post. (+9)
2. Get a little closer so her face fills more of the frame. (+7)
3. Lower the phone to his eye level. (+6)
4. Give her more room on the side she's looking toward. (+5)
5. Step back so nobody's feet get cut off. (+8)
6. Turn so the window is beside you, not behind them. (+6)
7. Tilt down a touch; there's more sky than you need. (+4)
8. Move until the tree isn't growing out of his head. (+7)
9. Stand in the middle of the doorway so both sides match. (+6)
10. Hold the phone right above the plate so it all fits. (+5)

## Identity

The approved board is "IDENTITY 03" (`brand/identity/brand-board.png`): a warm paper page, ink
type, one apricot accent, and an apricot card with the mark large and the caption *One quiet cue.
Everything settles.* The kit was delivered as a package (`brand/identity/`, `brand/site/`,
`brand/guidelines/`, `brand/source/build_identity.py`); `brand/guidelines/brand-guide.md` is the
designer's own guide and is authoritative for the assets. This section is the summary the app team
needs.

### The mark: the settled eye

An eye seen straight on, at rest: a ring (the iris, and the lens), a filled pupil, and one small
offset catchlight at the upper right of the pupil. The catchlight is the one warm point in an
otherwise quiet mark, the way the cue is the one thing on an otherwise clear screen.

- **On Paper** (`mark-primary.svg`): Ink ring and pupil, Apricot catchlight.
- **On Ink** (`mark-reverse.svg`): Paper ring and pupil, Apricot catchlight.
- **The app icon**: a full Apricot field with an Ink (charcoal) ring and pupil; the catchlight is a
  transparent cutout, so it shows the Apricot beneath. The adaptive background is full-bleed Apricot
  (`@color/sceneright_icon_background`); the launcher supplies the mask.
- **Single tint** (`mark-mono.svg`, `mark-white.svg`, the themed launcher icon): ring and pupil in
  the tint, the catchlight cut out of the pupil.
- Three shapes, never more. No gradient, no shadow, no lens flare, no eyelids or lashes. Never
  stretch or rotate the mark, never make the pupil a recording indicator, never animate the
  catchlight.
- Clear space: at least a quarter of the mark's outer diameter on all sides. Minimum standalone
  mark 24 px (16 px only as a favicon).

Adaptive icon geometry: 108 dp canvas; the essential art sits inside a centred 53.28 dp circle, well
within Android's 66 dp safe circle (`previews/launcher-masks.png` shows the circle, rounded-square
and square masks). At 48 dp the ring stroke renders about 4 dp and the pupil about 14 dp.

Files in the app: `app/src/main/res/drawable/ic_launcher_foreground.xml` and
`ic_launcher_monochrome.xml` (from `brand/identity/android/res/drawable/`),
`app/src/main/res/values/colors.xml` (the icon background and the app colour system below), and the
two `mipmap-anydpi-v26` wrappers. The legacy raster `mipmap-*dpi` PNGs in the kit are not installed
because the app's minimum SDK is 26. The Play store icon is `brand/identity/app-icon.png`
(512 x 512 RGBA).

### Colour system

Two layers. They do different jobs and do not mix.

**1. Marketing palette** (`brand/identity/tokens.json`). For the icon, the site, the store graphics
and these docs.

| Name | Value | Use |
|---|---|---|
| **Ink** | `#252A31` | Wordmark, primary text, main buttons, icon foreground, dark brand surfaces |
| **Paper** | `#F7F3EC` | Main light surface; the reverse logo |
| **Apricot** | `#E9A17E` | App icon background; the catchlight, selected details, main actions |
| **Mist** | `#E2E5E4` | Supporting surfaces and section backgrounds |
| Secondary text | `#60666C` | Supporting text on Paper or Mist |

Rules:

- Roughly 75 % neutral surface, 20 % Ink, 5 % Apricot in brand layouts.
- **Apricot is never text on a light surface.** Ink on Apricot (6.8:1) and Paper on Ink (13:1) are
  the text pairings. Ink on Paper is about 13:1; secondary text on Paper about 5.3:1.
- Colour is never the only signal for alignment, readiness, selection or errors.

**2. The app colour system** (approved 19 October 2026, in `app/.../ui/theme/Color.kt` and
`app/src/main/res/values/colors.xml`). Inside the live camera the surfaces stay dark and neutral so
the photograph provides the colour. Main actions use Apricot with Ink text. The shutter and
essential text are neutral white.

| App role | Value | Token |
|---|---|---|
| Camera background | `#15171B` | `Background` |
| Controls and sheets | `#24282E` | `Surface` |
| Primary text and the shutter | `#F2F3F4` | `OnSurface` |
| Secondary text | `#B7BDC4` | `OnSurfaceMuted` |
| Main action fill | `#E9A17E` | `Accent.Action` (Material `primary`) |
| Main action text | `#252A31` | `onPrimary` |
| Ready (the ring turns ready, Keeper, a level horizon) | `#34D399` | `Accent.Ready` (unchanged) |
| Getting there (the mid score tier, Good, Try next time) | `#FFC857` | `Accent.Warn` (unchanged) |

Ready and Warn are functional states and keep their validated values; Apricot is the brand accent
and never replaces them. Cue text sits on opaque neutral chips. Photographs are never washed in brand
colour. Paper remains available for onboarding, longer reading and marketing; the review shown right
after capture keeps the camera's dark surfaces.

### Type

- In the app: the system sans (Roboto on most Android phones), Material 3 scale as today; score
  numerals tabular. Body copy at least 16 sp, live cues 18-22 sp, touch targets at least 48 dp; cue
  text is never shrunk to save a layout.
- Marketing and the site: DejaVu Sans for text, controls and cues; DejaVu Serif for large brand
  headlines only, never for a live cue. The web fonts and their licence are bundled in
  `brand/site/assets/` (no remote font service).
- The wordmark is outlined DejaVu Sans with custom spacing; it needs no font installed.

### Wordmark and lockup

- **Marketing wordmark:** "sceneright", all lowercase, one word, with the small spaced descriptor
  **"photo"** under the end of the name. The descriptor is a visual element, not part of the name.
- **In prose and as the launcher label:** "SceneRight", capital S and R. Never "Scene Right",
  "Sceneright" or "SCENERIGHT" in writing. Say it as "scene right". The store title is
  "SceneRight: Camera Coach".
- **Lockups** (`brand/identity/logo-primary.svg` on Paper, `logo-reverse.svg` on Ink,
  `logo-mono.svg` and `logo-white.svg` single-colour with the catchlight cut out; PNG exports beside
  them). Minimum lockup width 240 px with the descriptor; below that use `wordmark-*.svg` (no mark,
  no descriptor) or the mark alone.
- All lettering in the SVGs is outlined, so nothing depends on installed fonts.

### Feature graphic and site

- `brand/identity/play-feature-graphic.png` (1024 x 500, RGB, from the SVG beside it): the lockup,
  the tagline and the support line on Paper at left, the mark on an Apricot pill at right.
- `brand/site/`: a static landing page (`index.html`, `styles.css`, `site.js`, local assets, bundled
  fonts; no third-party requests). Its copy still describes the app as in development and its three
  truths as planned, because the kit was written before the app shipped: before it goes public,
  switch that copy to the present tense, add the store or release link, and replace the meadow
  illustration with real captures. `docs/SITE_DEPLOYMENT.md` and `scripts/deploy-site.sh` deploy
  it to the VPS; the social preview URL in `index.html` points at the planned hostname.

### Photography in marketing

Real photos taken with the app, by ordinary people, unretouched beyond what the app does. Show the
card next to the photo. No stock models, no golden-hour clichés unless someone actually took it.
