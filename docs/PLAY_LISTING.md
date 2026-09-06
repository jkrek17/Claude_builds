# Play Store listing — Composition Coach

Everything below is copy-ready for the Play Console "Store listing" and "App content" sections. Values
that require an actual account/console action (screenshots, the signed Data Safety submission, content
rating questionnaire submission) are marked **[MANUAL]**.

## Title

```
Composition Coach
```

## Short description (≤ 80 characters)

```
Real-time on-device photo composition coach. No cloud, no accounts, no ads.
```

(76 characters.)

## Full description

```
Composition Coach watches your live camera preview and coaches your framing before you press the
shutter — a 0-100 composition score plus one clear instruction at a time, like "move slightly right"
or "level the horizon". When the shot is dialed in, the score turns green and tells you to shoot.

HOW IT COACHES YOU
• A live composition score (0-100) that updates as you move the camera
• Plain-language directional advice: move left/right, raise/lower the camera, rotate to level the
  horizon, leave more room for your subject to look into, step back to avoid cropping — one instruction
  at a time so it's never overwhelming
• A "shoot" cue when the framing is strong
• Shooting modes — Auto, Portrait, Group, Landscape, Architecture, Object — so the coach knows what
  you're going for
• An optional "Coach" guidance level that explains the photographic reasoning behind each suggestion
• A rule-of-thirds grid overlay
• A review screen after every photo showing what the shot did well and what could improve

BUILT FOR PRIVACY, ON YOUR DEVICE
• All analysis runs locally on your phone. Composition Coach has no network permission and cannot
  send anything anywhere — there are no accounts, no analytics, and no ads.
• Photos you take are saved straight to your own gallery (Pictures/CompositionCoach), exactly like any
  other camera app — nothing is uploaded.
• Face/pose/object detection uses Google's on-device ML Kit; frames are scored and immediately
  discarded, never stored or transmitted.

WHO IT'S FOR
Anyone who wants their photos to look more intentional without studying composition theory — portraits,
group shots, travel and landscape photography, architecture, and everyday snapshots.

Composition Coach is a coaching tool, not a photo editor: it doesn't touch your images, add filters, or
require an internet connection to work.
```

## Category

```
Photography
```

## Contact details

**[MANUAL]** — the developer account's support email, and optionally a website/repository URL, are
entered directly in Play Console (Store presence → Store settings) by the account owner.

## App icon / feature graphic / screenshots

Required assets (Play Console → Grow → Store presence → Main store listing):

- **App icon**: 512×512 PNG, 32-bit with alpha — export from `app/src/main/res/drawable/ic_launcher_foreground.xml`
  + `ic_launcher_background.xml` composited (or re-render the adaptive icon at 512×512). **[MANUAL]**
- **Feature graphic**: 1024×500 PNG/JPEG, no alpha. **[MANUAL — design asset, not produced by this repo.]**
- **Phone screenshots**: minimum 2, recommended 4-8, 16:9 or 9:16, JPEG/24-bit PNG, each side
  320-3840px. Suggested shots (all **[MANUAL]**, captured on a real device per README's "Build from
  source" instructions):
  1. Camera screen mid-coaching: live preview, score badge showing a mid-range score (e.g. 62), and a
     directional instruction ("Move slightly right →") visible.
  2. Camera screen in the green "shoot-ready" state (score ≥ 88, "— SHOOT" badge).
  3. The rule-of-thirds grid overlay turned on.
  4. The review screen after a capture, showing the photo, score, "Strong" and "Could improve" lists.
  5. Settings screen showing the shooting-mode chip row (Auto/Portrait/Group/Landscape/Architecture/Object).
  6. Settings screen scrolled to the About section (app name, version, privacy policy link) — demonstrates
     the privacy-first positioning directly in a screenshot.
  7. (Optional) Debug mode's overlay panel, if you want to visually back up the "on-device analysis"
     claim with real metric names — not required for the listing.
- **Tablet/Chromebook screenshots**: not required (the app is portrait-phone-only in this MVP; see
  README's "Roadmap" section on landscape orientation as a future enhancement).

## Content rating (IARC questionnaire notes) — **[MANUAL submission, notes below]**

The IARC questionnaire is submitted by the account owner in Play Console (Policy → App content →
Content ratings). Composition Coach:

- Contains no violence, sexual content, profanity, gambling, or controlled-substance references.
- Contains no user-generated content, no chat/messaging, no social features.
- Uses the camera for local, on-device analysis only — no user account, no data sharing.

Expected outcome: the lowest available rating tier in every region (e.g. PEGI 3 / ESRB Everyone), but
the actual rating is only assigned once the questionnaire is submitted through Play Console.

## Target audience and content

- Not designed for or directed at children (general audience app; standard age rating flow applies).
- No ads, no in-app purchases, no user-generated content.

## Data safety form answers

Play Console → Policy → App content → Data safety. Composition Coach **does not collect or share any
user data** — the form should be filled out as follows:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Does your app encrypt user data in transit? | N/A (no data leaves the device; no network permission) |
| Do you provide a way for users to request that data be deleted? | N/A (nothing is collected) |
| Camera permission — is it used, and for what? | Used for the app's core feature (live composition analysis and taking photos); not for data collection — process entirely on-device, nothing transmitted. |
| Photos and videos | Saved by the app to the user's own device gallery, acting as a normal camera app. Not collected by the developer, not shared with any third party. |

See `docs/PRIVACY_POLICY.md`'s "Data safety summary" table for the same answers in policy-document form,
and paste the URL to that policy (once published, e.g. as a GitHub Pages / raw file link) into the
"Privacy policy" field in Store settings. **[MANUAL — the actual submission in Play Console is a
one-time account-owner action; this repo can only supply the answers.]**

## Permissions declared (for the "why does this app need X" reviewers sometimes ask about)

| Permission | Why |
|---|---|
| `android.permission.CAMERA` | Core feature: live preview analysis and photo capture. |
| `android.permission.WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28) | Only requested on Android 9 and below, right before the first capture, to save the photo to the public Pictures folder. Android 10+ uses scoped storage (MediaStore) and never requests this. |

No other permission is requested. `INTERNET` is explicitly stripped from the
merged manifest (see `app/src/main/AndroidManifest.xml`) even though a transitive ML Kit dependency
would otherwise add them — the app has no legitimate use for them, and a Gradle check
(`verifyNoInternetPermission`) fails the build if either ever reappears.

## Pricing and distribution

- Free, no in-app purchases, no ads.
- Countries: **[MANUAL — account owner's choice in Play Console]**.
