# SceneRight: brand book

The app formerly called Composition Coach. This is the reference for the name, the story, the voice,
the in-app copy and the identity. Naming rationale, collision checks and the final decision:
`brand/NAMING.md`. How to adopt the name in the repo: `brand/RENAME.md`. Assets: `brand/identity/`,
`brand/site/`. In-app strings live in `app/src/main/res/values/strings.xml`.

- **Name:** SceneRight (one word, capital S and capital R in prose and as the launcher label; the
  marketing wordmark is set "sceneRight" with the descriptor "photo").
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

The approved board is "IDENTITY 01": a warm paper page, ink type, one apricot accent, and a dark
rounded card with the mark large and the caption *One quiet cue. Everything settles.*

### The mark: the settled eye

An eye seen straight on, at rest: a ring (the iris, and the lens), a filled pupil, and one small
apricot catchlight at the upper right of the pupil. A catchlight is the small bright reflection that
makes an eye in a portrait look alive; it is the one warm point in an otherwise quiet mark, the way
the cue is the one thing on an otherwise clear screen.

- **On Paper:** Ink ring, Ink pupil, Apricot catchlight.
- **On Ink:** Paper ring, Paper pupil, Apricot catchlight (the launcher icon and the dark card).
- **Single tint** (themed icon, embossing, one-colour print): ring and pupil in the tint, the
  catchlight cut out of the pupil.
- Three shapes, never more. No gradient, no shadow, no lens flare, no eyelids or lashes.

Geometry, on the 108 dp adaptive-icon canvas (centre 54,54), and scaled as a unit everywhere else:

| Shape | Centre | Size |
|---|---|---|
| Ring | 54, 54 | r 22, stroke 6 (outer edge r 25, inner edge r 19) |
| Pupil | 54, 54 | r 12, filled (7 dp clear gap to the ring) |
| Catchlight | 58, 50 | r 4, filled (farthest point 9.66 from centre, inside the pupil) |

- Safe-zone compliant: everything sits inside r 25, well within the 66 dp safe-zone circle (r 33), so
  no launcher mask clips it.
- Reads at 48 dp: the ring stroke renders about 4 dp, the pupil about 16 dp, the catchlight about
  5 dp across.

Files: `brand/identity/ic_launcher_foreground.xml`, `ic_launcher_background.xml` (flat Ink),
`ic_launcher_monochrome.xml` (drop-in replacements for the files of the same name in
`app/src/main/res/drawable/`), and `icon-preview.svg` (512 px, full square, for the Play store icon;
export to 32-bit PNG).

### Colour system

Two layers. They do different jobs and do not mix.

**1. Marketing palette.** For the launcher icon, the site, the store graphics and these docs.

| Name | Value | Use |
|---|---|---|
| **Ink** | `#252A31` | Text, the mark on light grounds, the dark card, the icon background, buttons |
| **Paper** | `#F7F3EC` | The page ground; the mark and text on Ink |
| **Apricot** | `#E9A17E` | The single accent: the catchlight. Also small non-text marks (a dot, a rule) |
| **Mist** | `#E2E5E4` | Hairlines, swatch borders, secondary surfaces (cards on Paper) |

Rules:

- Paper is the ground, Ink is the voice, Apricot is the catchlight. One Apricot moment per view.
- **Apricot is never text on Paper** below display size (it is about 1.9:1). Apricot on Ink is about
  6.8:1 and may carry short display text if ever needed, but the default is: Apricot is a shape, not
  words.
- Contrast: Ink on Paper about 13:1, Ink on Mist about 11:1, Paper on Ink about 13:1. Secondary text
  is Ink at 68-76 % opacity (4.9:1 to 6.3:1 on Paper); never lighter.
- Mist on Paper is about 1.15:1, so Mist only separates surfaces; it never carries meaning alone.

**2. In-app tokens, unchanged.** The app's own colours (`app/.../ui/theme/Color.kt`) **do not
change** with the rename. The camera chrome stays black, with Accent.Ready and Accent.Warn as today,
because colour drawn over a live photo must stay legible whatever the scene, and warm paper or
apricot over a sunset would disappear. The only place the marketing palette touches the app is the
launcher icon (Ink background).

| Token | Value | Use |
|---|---|---|
| Background | `#000000` | Ground behind the camera preview and screens |
| Surface | `#121212` | Cards, sheets |
| OnSurface | `#F2F2F2` | Text on dark |
| OnScrimMuted | `#FFFFFF` at 70 % | Secondary text, labels |
| Scrim | `#000000` at 40 % | Anything drawn over a photo or preview |
| ScrimStrong | `#000000` at 85 % | Full-screen states over a photo |
| **Accent.Ready** | `#34D399` | Ready, worked, Keeper, the shutter ring. Means "yes". |
| **Accent.Warn** | `#FFC857` | Getting there, Good, Try next time. Means "almost". |
| Ink on accent | `#1A1A1A` | Text on a green or amber fill (the app's `onPrimary`) |

In-app rules (as today): green and amber are signals, never decoration; no third accent inside the
app; photos are the only other colour. When marketing shows the app (a phone mockup, the after-the-shot
card), it shows these real in-app colours, framed on Paper.

### Type

- In the app: the system sans (Roboto on most Android phones), Material 3 scale as today; score
  numerals tabular.
- Marketing: Inter where available, else Roboto, else the platform sans, via font-family stacks; no
  webfont loads. Weights: Regular (400), Medium (500) for the descriptor and small captions, Semibold
  (600) for the name and headings. Tight tracking on display sizes (-1 to -3 %), none on body.

### Wordmark and lockup

- **Marketing wordmark:** "sceneRight": lowercase s, capital R, one word, Semibold. Under the right
  part of the name sits the descriptor **"photo"**: Medium, small (about a quarter of the name's
  size), widely spaced, right edge aligned with the end of the name, below the descenders.
- **In prose and as the launcher label:** "SceneRight". Never "Scene Right", "Sceneright" or
  "SCENERIGHT". The store title is "SceneRight: Camera Coach".
- **Lockup** (`brand/identity/wordmark.svg`): the mark at left (on Paper: Ink ring and pupil, Apricot
  catchlight), then the name and descriptor in Ink. Clear space: half the mark's width on every side.
  Minimum: 24 px mark height. Drop the "photo" descriptor when the name is set smaller than about
  28 px (site header, footer); the mark and name alone are the small lockup.
- **Text only** (`brand/identity/wordmark-text.svg`): name and descriptor, transparent ground, Ink;
  swap to Paper on Ink grounds.
- Outline the text before print or PNG export so it does not depend on installed fonts.

### Feature graphic and site

- `brand/identity/feature-graphic.svg`: 1024 x 500 on Paper: the lockup, the header line, the tagline
  and the support line at left; the dark card with the mark large and *One quiet cue. Everything
  settles.* at right. Export as PNG or JPEG without alpha for Play.
- `brand/site/index.html`: the landing page, single file, no external fonts or scripts. Paper page,
  Ink type, the dark card section with the voice samples, and the app shown in its own in-app colours.
  It has two placeholders: the Play Store link and the privacy policy link.

### Photography in marketing

Real photos taken with the app, by ordinary people, unretouched beyond what the app does. Show the
card next to the photo. No stock models, no golden-hour clichés unless someone actually took it.
