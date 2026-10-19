# Play Store listing: Eyebright

Copy-ready text for Play Console "Main store listing" and "App content". Voice and claims follow
`docs/BRAND.md`. Values that need a Console action by the account owner are marked **[MANUAL]**.

The package name stays `com.compositioncoach.app` (see `brand/RENAME.md`); only the display name
changes. Play never shows the package name to users.

## Publish gate: claims that depend on Wave 2

The listing describes the product as defined in `docs/PRODUCT.md`. Some of it is Wave 2 work. Play
policy forbids describing features the app does not have, so until each feature ships, remove its
lines (or use the interim line).

| Claim in the listing | Depends on | Interim line until it ships |
|---|---|---|
| "takes it at the best moment", "Tap the shutter and Eyebright waits..." | Shoot-at-peak | "When the ring around the shutter turns green, take the shot." |
| "levels the horizon and picks the sharpest frame" | Auto-level, sharpest-of-burst | Remove the line |
| Verdict, "One thing to try next time", "Better crop?" | After-action card, `CropSuggester` | "After every photo, a short card shows what worked and what could be better." |
| Whole "It learns what you like" section, "Your eye" screenshot | `AdaptiveWeights`, `PhotoJournal`, "Your eye" | Remove the section and screenshot 7 |
| Plain-word intents (A person, A view...) | Wave 2 mode-strip labels | "Tell it what you're shooting: Portrait, Group, Landscape, Architecture, Object, or Auto." |
| Coach mode, manual controls switch | Easy/Coach and Pro settings | Remove the "Want more?" section |

## Title (≤ 30 characters)

```
Eyebright: Camera Coach
```

(23 characters. The descriptor helps search: "eyebright" alone returns eye-care apps.)

## Short description (≤ 80 characters)

```
The camera that frames for you, then shows you why. All on your phone.
```

(70 characters.)

## Full description (≤ 4000 characters)

```
Eyebright is a camera for people who love photos but never learned photography. It frames the shot for you, takes it at the best moment, and then shows you, in your own photo, what worked and one thing to try next time.

Everything happens on your phone. Eyebright has no internet permission, no account, no ads and no analytics. Your photos never leave your device.

POINT. TAP ONCE.
• One quiet cue shows you where to move: a little left, closer, level. One to three words, never a paragraph.
• Tap the shutter and Eyebright waits until the framing is right and your hand is steady, then takes the photo. Hold to shoot right away.
• It levels the horizon and picks the sharpest frame without asking.

SEE WHY IT WORKS
After every photo, a short card you can read in five seconds:
• A verdict: Keeper, Good or Try again.
• What worked, in plain words: "Level horizon", "Nice light on her face".
• One thing to try next time, never a list: "A step to the left would have cleared the lamp post."
• Better crop? Up to three suggestions, saved as a copy. Your original is never touched.

IT LEARNS WHAT YOU LIKE
• Eyebright notices what you keep, what you retake, and which advice you take or skip.
• Love centred portraits? It stops telling you to move off-centre.
• The Your eye screen shows how you're doing in plain words: keepers this month, what you tend to like, your best three. No streaks, no badges.
• Start fresh any time with one tap. What it learns stays on your phone.

TELL IT WHAT YOU'RE SHOOTING, OR DON'T
Anything · A person · A group · A view · A building · Food & things. When you don't say, it guesses.

PRIVATE BY DESIGN
• All analysis runs on your phone. The app cannot connect to the internet: the permission is removed, and every build is checked for it.
• No account. No ads. No analytics. No tracking.
• Photos save to your own gallery, like any camera app.
• On first launch, Google Play services may download the face-detection model the app uses on your phone. Eyebright itself sends nothing.

WANT MORE?
Coach mode adds a short reason under each cue. One switch in Settings reveals manual controls: exposure, zoom presets and a grid. Easy is the default, and you never have to look.

Needs Android 8.0 or newer with Google Play services.
```

(2,301 characters.)

## Category and tags

- Category: **Photography**
- Tags (Console, up to five): Camera, Photography. Not "Photo editor": the app does not edit
  photos beyond saving an optional crop as a copy.

## Contact details

**[MANUAL]**: support email (required) and website (optional, e.g. the landing page in
`brand/site/` once hosted) in Play Console → Store presence → Store settings.

## Graphics

| Asset | Spec | Source |
|---|---|---|
| App icon | 512 x 512, 32-bit PNG | `brand/identity/icon-preview.svg` → export **[MANUAL]** |
| Feature graphic | 1024 x 500, PNG or JPEG, no alpha | `brand/identity/feature-graphic.svg` → outline text, export **[MANUAL]** |
| Phone screenshots | 8, portrait 9:16, 1080 x 1920 or larger, JPEG or 24-bit PNG | Shot list below, captured on a real phone **[MANUAL]** |

Screenshots: real photos taken with the app, no mocked UI. Caption bar at the top of each frame:
black ground, OnSurface text, Inter or Roboto Semibold, one line, no more than six words. The phone
screen fills the rest. No device frame needed.

### Screenshot shot list

| # | What is on screen | Caption |
|---|---|---|
| 1 | Camera, Easy mode: a person crowding the left edge, the left-edge chevron breathing, chip "Slightly left". Shutter ring part-filled, amber. | One quiet cue. Just follow it. |
| 2 | Same scene a second later: subject placed, ring full and green, "SHOOT" chip above the shutter. | Tap once. It waits for the moment. |
| 3 | After-the-shot card: photo on top, ring with 86, "Keeper", three green "What worked" lines. | See why it works, in plain words. |
| 4 | Card scrolled to "Try next time", one amber line: "A step to the left would have cleared the lamp post. (+9)"; the "What if" overlay held showing "72 → 83". | One thing to try next time. |
| 5 | "Better crop?" row: three crop thumbnails with their scores, one selected with the dashed outline on the photo. | Better crops, saved as a copy. |
| 6 | Camera with the intent strip in view: Anything · A person · A group · A view · A building · Food & things, "A view" selected, bubble level green on a landscape. | Tell it what you're shooting. |
| 7 | "Your eye": This month, 38 keepers, Level horizon 9 in 10 times, What you like, Your best three. | It learns what you like. |
| 8 | Settings footer "Everything happens on your phone. Nothing is uploaded." with the phone's airplane-mode icon visible in the status bar while the camera works. | Everything stays on your phone. |

Tablet and Chromebook screenshots: not needed (phone-only, portrait).

## Content rating (IARC questionnaire) **[MANUAL submission]**

- No violence, sexual content, profanity, gambling, or controlled substances.
- No user-generated content shared with others, no chat, no social features, no location sharing.
- Camera is used for on-device analysis and taking photos; no account, no data sharing.

Expected outcome: the lowest tier everywhere (PEGI 3, ESRB Everyone). The actual rating is assigned
only after submission in Play Console → Policy → App content → Content ratings.

## Target audience and content

- General audience; not designed for or directed at children.
- No ads, no in-app purchases, no user-generated content.

## Data safety form answers

Play Console → Policy → App content → Data safety. Eyebright **collects no user data and shares no
user data**. Data processed only on the device is not "collected" under Play's definition, and the
app cannot transmit anything: `INTERNET` is removed from the merged manifest and
`verifyNoInternetPermission` fails the build if it returns.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable: nothing is collected or transmitted (no network permission). |
| Do you provide a way for users to request that their data is deleted? | Not applicable: nothing is collected. (On the phone, Settings and "Your eye" → Start fresh clear the app's own preferences; uninstalling removes everything.) |
| Camera | Used for the core feature (framing guidance and taking photos). Frames are analysed on the phone and discarded; never stored by the developer or transmitted. |
| Photos and videos | Saved by the app to the user's own gallery, acting as a camera. Not collected by the developer, not shared. |
| App activity (Wave 2 photo journal: keeps, retakes, cues followed) | Not collected: stored only in the app's private storage on the phone, never transmitted. See the backup note below. |
| Crash logs / diagnostics | Not collected: the crash screen shows the log on the phone; the user may copy or email it themselves. |

Note for the integrator before Wave 2 ships: the manifest has `android:allowBackup="true"`, which lets
Android's own backup copy app data to the user's Google account. To keep the listing's "never leave
your device" literally true, turn backup off or exclude the journal and preferences (`docs/BRAND.md`,
"The three truths").

Privacy policy URL: publish `docs/PRIVACY_POLICY.md` (renamed per `brand/RENAME.md`) at a public URL
and paste it into Store settings **[MANUAL]**.

## Permissions declared

| Permission | Why |
|---|---|
| `android.permission.CAMERA` | Core feature: live framing guidance and taking photos. |
| `android.permission.WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28) | Only on Android 9 and older, to save photos to the public Pictures folder. Android 10+ uses MediaStore and never asks. |
| `android.permission.ACCESS_NETWORK_STATE` (normal, no prompt) | Required by the bundled ML Kit library, which queries connectivity state. Grants no network access on its own. |

`android.permission.INTERNET` is explicitly removed (`tools:node="remove"` in
`app/src/main/AndroidManifest.xml`) even though an ML Kit dependency would add it; the build fails if
it ever reappears.

## Pricing and distribution

- Free. No in-app purchases. No ads.
- Countries: **[MANUAL]**, account owner's choice.
