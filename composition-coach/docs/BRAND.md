# Eyebright: brand book

The app formerly called Composition Coach. This is the reference for the name, the story, the voice,
the in-app copy and the identity. Naming rationale and collision checks: `brand/NAMING.md`. How to
adopt the name in the repo: `brand/RENAME.md`. Assets: `brand/identity/`, `brand/site/`,
`brand/strings/`.

- **Name:** Eyebright (one word, capital E in prose; the wordmark is set lowercase).
- **Tagline:** The photo you meant to take.
- **Support line:** Frames for you. Shows you why.
- **One sentence:** Eyebright is a camera that frames the shot for you, takes it at the best moment,
  and then shows you, in your own photo, what worked and one thing to try next time, all on your phone.

## Brand story

You see something worth keeping: your daughter laughing, the light on the hills, a plate you're proud
of. You raise your phone, and somehow the photo never looks like the moment did.

Eyebright is a camera that sees with you. Point it, and one quiet cue shows you where to move. Tap
once, and it waits for the instant everything lines up, then takes the picture. It straightens the
horizon and picks the sharpest frame without asking.

Afterwards, it shows you why the photo works, in plain words, and one thing to try next time. Not a
lesson. Just your own picture, explained.

Over time it notices what you keep and what you skip, and it learns your taste instead of arguing
with it. Your eye gets sharper. Your photos start to look like you meant them.

And all of it happens on your phone. Nothing leaves it.

*(148 words.)*

## Positioning statement

For people who love taking photos but never learned photography, **Eyebright** is the camera that
frames the shot for you and then shows you, in your own pictures, what worked and what to try next.
Unlike filter apps, which change the photo, and pro camera apps, which hand you more controls,
Eyebright changes the photographer, gently: it teaches from your own shots, learns your taste, and
does all of it on your phone.

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
| **It stays on your phone.** | "Everything happens on your phone. Nothing is uploaded." | The app has no internet permission; a build check (`verifyNoInternetPermission`) fails the build if one ever appears. No account, no analytics, no ads. Demo: it works the same in airplane mode. | **True today.** Two caveats below. |
| **It shoots at the best moment.** | "Tap once. It takes the photo when everything lines up." | Tap arms the shutter; it fires when the framing is ready and the phone is steady, or after 4 seconds; hold to shoot at once. Demo: side by side with a plain tap. | **Wave 2** (`PRODUCT.md`, shoot-at-peak). Today the ring turns green and you shoot yourself. Do not claim until shipped. |
| **It learns your taste.** | "It learns what you like." | Keeps, retakes and the cues you follow or skip nudge bounded preferences, on the phone. The "Your eye" screen shows what it learned in plain words; one tap resets it. | **Wave 2** (`AdaptiveWeights`, `PhotoJournal`, "Your eye"). Do not claim until shipped. |

Caveats on the first truth, for the integrator (do not change the claim, fix the app):

1. **Android backup.** The manifest has `android:allowBackup="true"`, so Android's own backup can copy
   the app's private data (settings today, the photo journal and taste model in Wave 2) to the user's
   Google account. To keep "nothing leaves your phone" literally true, set `allowBackup="false"` or
   exclude the journal and preferences with backup and data-extraction rules before Wave 2 ships.
2. **Face model download.** On first launch Google Play services downloads the face-detection model.
   That is Play services fetching a model, not the app sending anything, and the privacy policy already
   explains it. Marketing copy needs no change; the FAQ answer is in the privacy policy.

## What we never say

| Never | Because | Say instead |
|---|---|---|
| AI-powered, AI camera, smart, intelligent, algorithm | It is a claim about us, not about your photo, and the category is full of it | "It sees where to move." "It learns what you like." |
| Pro, professional, like a pro, DSLR-quality | Our people are not pros and do not want to be told to become one | "The photo you meant to take." |
| Rule of thirds, composition, leading lines, negative space (in marketing) | Jargon. Easy mode never says it; Coach mode may, inside the app only | "Framing", "where to stand", "eyes a little higher" |
| Perfect shot, flawless, magic | Untrue and unearnable | "A keeper." |
| Score as a judgement ("your photo scored 54") | The number is a tool, the verdict is the message | "Try again." "Good." "Keeper." |
| Wrong, bad, mistake, fix your photo | We never judge the person or argue with their taste | "Try next time: ..." |
| Streak, badge, level up, unlock, achievement | No gamification; this is a camera | "This month: 38 keepers." |
| Content, followers, likes, viral, influencer | Not a social app | "Photos", "your pictures" |
| Cloud, sync, account | We have none, and the words make people wonder | "On your phone." |
| Revolutionary, game-changing, the best camera app | Never compare up, never shout | Show the card, let it speak |

## Voice and tone

**One idea:** a calm friend with a good eye, standing next to you, who only speaks when it helps.

1. **Live, the fewest words.** One cue, 1-3 words, a physical action: *Slightly right. Closer.
   Level.* No sentences, no reasons, no punctuation except none.
2. **After, plain sentences.** Short, concrete, about this photo. A subject you can see and an action
   you can do with your feet or your hands.
3. **Physical, not photographic.** Step, raise, lower, closer, turn. Not "reposition the subject".
4. **Credit the person.** "You kept 38." Not "Eyebright took 38."
5. **One thing at a time.** Always one "Try next time", never a list.
6. **The camera never says "I".** Eyebright is named in the third person when needed; "we" only on
   the website and in the store listing.
7. **Warm, not cute.** No exclamation marks in the app. No puns. No emoji.
8. **Never argue with taste.** When someone keeps ignoring a cue, the app mentions it less; it does not
   explain why they are wrong.

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

The XML for all of this is in `brand/strings/strings.xml`, ready for the integrator.

### Permission screen

- Title: **Eyebright needs your camera**
- Body: It looks through the lens to help you frame. Everything stays on your phone. Nothing is
  uploaded.
- Button: **Allow camera**
- Permanently denied: Camera access is off. Turn it on in Settings to start. Button: **Open settings**

### Onboarding (one card, first camera session, never again)

- Title: **Point. Tap once.**
- Body: Follow the one cue on screen. Tap the shutter and Eyebright takes the photo when everything
  lines up. Hold to shoot right away.
- Line under it: After each photo, see what worked and one thing to try next time.
- Button: **Start**

Until shoot-at-peak ships, the current card stays, reworded: *Point · Follow the cue · Shoot when it
turns green* / **Got it**.

### Verdict words

**Keeper · Good · Try again.** Capitalised as shown, no punctuation. On the card the verdict sits
beside the score ring and nothing else does; the ring keeps the live arc's tier colours.

- Keeper: the photo you meant to take.
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
- Section: **Advice you skip most**: Give looking room. *That's fine. Eyebright will mention it less.*
- Section: **Your best three** (each opens its card)
- Footer: Learned on your phone. Never uploaded. · **Start fresh**
- Start fresh dialog: **Start fresh?** Eyebright forgets what it has learned about your taste. Your
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

### Icon concept: the bright eye

The shutter's readiness ring at the moment it turns green, with one white catchlight in it. A
catchlight is the small bright reflection in an eye that makes a portrait look alive; it is what
"eyebright" looks like. The ring is the app's own shutter ring, so the icon is the moment you are
waiting for: ready.

- Two shapes, two colours, on Surface #121212. No gradient, no shadow, no lens flare.
- Reads at 48 dp: the ring stroke renders about 4 dp, the catchlight about 7 dp.
- Safe-zone compliant: everything sits inside a 50 dp circle, well within the 66 dp safe zone, so no
  launcher mask clips it.
- Themed (monochrome) icon: the same two shapes in a single tint.

Files: `brand/identity/ic_launcher_foreground.xml`, `ic_launcher_background.xml`,
`ic_launcher_monochrome.xml` (drop-in replacements for the files of the same name in
`app/src/main/res/drawable/`), and `icon-preview.svg` (512 px, full square, for the Play store icon;
export to 32-bit PNG).

### Colour system

The app's existing tokens (`app/.../ui/theme/Color.kt`), unchanged. The brand adds no colour.

| Token | Value | Use |
|---|---|---|
| Background | `#000000` | Ground everywhere: the camera, the site, the feature graphic |
| Surface | `#121212` | Cards, the icon background, the phone in mockups |
| OnSurface | `#F2F2F2` | Text and the wordmark on dark |
| OnScrimMuted | `#FFFFFF` at 70 % | Secondary text, labels |
| Scrim | `#000000` at 40 % | Anything drawn over a photo or preview |
| ScrimStrong | `#000000` at 85 % | Full-screen states over a photo |
| **Accent.Ready** | `#34D399` | Ready, worked, Keeper, the icon ring. Means "yes". |
| **Accent.Warn** | `#FFC857` | Getting there, Good, Try next time. Means "almost". |
| Ink on accent | `#1A1A1A` | Text on a green or amber fill (the app's `onPrimary`) |
| Hairline | `#FFFFFF` at 12-14 % | Dividers and device outlines in marketing only |

Rules:

- Green and amber are signals, never decoration. Green only where something is ready or worked;
  amber only where something is almost there. Never both as a gradient.
- No third accent. Photos are the only colour beyond these.
- Contrast: Ready on black is about 11:1, Warn on black about 13:1, OnSurface on Surface about 16:1;
  all far above 4.5:1.

### Type

- In the app: the system sans (Roboto on most Android phones), Material 3 scale as today; score
  numerals tabular.
- Marketing: Inter where available, else Roboto, else the platform sans. Two weights only: Regular
  (400) and Semibold (600). Tight tracking on display sizes (-1 to -2 %), none on body.
- The wordmark: "eyebright", lowercase, Semibold, tracking -1.5 at 80 px.

### Wordmark and lockup

- `brand/identity/wordmark.svg`: mark plus wordmark (the lockup). Clear space: one ring diameter all
  round. Minimum: 24 px mark height.
- `brand/identity/wordmark-text.svg`: wordmark alone, transparent ground.
- On light grounds: text `#121212`, ring stays green, catchlight `#121212`.
- Outline the text before print or PNG export so it does not depend on installed fonts.

### Feature graphic and site

- `brand/identity/feature-graphic.svg`: 1024 x 500, lockup, tagline, support line and a phone
  showing the after-the-shot card. Export as PNG or JPEG without alpha for Play.
- `brand/site/index.html`: the landing page, single file, no external fonts or scripts.

### Photography in marketing

Real photos taken with the app, by ordinary people, unretouched beyond what the app does. Show the
card next to the photo. No stock models, no golden-hour clichés unless someone actually took it.
