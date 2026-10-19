# Wave 2: the after-action product

Reshaped around `PRODUCT.md`: Easy mode live, coaching after the shot, learning what the user likes.
Priority order: foundation → after-action card + "Try next time" → crops and what-if → taste model and
"Your eye" → recipes (Motion first) → group assist → heads-up guidance → ghost → background separation.

Features that only a composition coach can offer. Each entry states the user-facing behaviour, the
owning module(s), the contract between modules, and the tests. Visual language per `APP_UX.md` and
`COACHING_UI.md`: restrained, one cue at a time, words are labels.

## Foundation: analysing a still photo

Everything on the review screen below needs the engine's view of the *captured* photo, not the last
preview frame.

- `vision`: `StillImageAnalyzer.analyze(bitmap, isFrontCamera): FrameAnalysis` runs face, object and
  segmentation detectors on a `Bitmap` (ML Kit `InputImage.fromBitmap`) plus `ImageStatisticsComputer`
  on its luminance, at a working size of ~1024 px on the long edge (downscale first; geometry is
  normalized so resolution does not matter). Synchronous-suspend, `Dispatchers.Default`. Orientation:
  the bitmap is already upright (decoded honouring EXIF), so rotation is 0 and `deviceRotationDegrees`
  is 0.
- `app/camera`: `CaptureRepository.decodeForAnalysis(uri): Bitmap` (EXIF-aware, downscaled) and
  `exportCrop(uri, NormalizedRect, suffix): Uri` writes a lossless-as-possible cropped copy (decode full
  size, crop, re-encode at quality 100, copy EXIF, same folder, name `<original>_crop.jpg`).
- `di/ReviewStore`: `ReviewEntry.stillAnalysis: FrameAnalysis?` and `stillResult: CompositionResult?`
  filled asynchronously after capture; the review screen shows the live-frame result until they arrive.

## 0. After-action card and "Try next time"

- `composition`: `AfterActionReport.build(result, optimization): AfterActionReport(verdict: Verdict
  (KEEPER ≥ 80, GOOD 60..79, TRY_AGAIN < 60, adjusted by HIGH severity), worked: List<String> (≤3),
  tryNextTime: TryNextTime?(sentence, predictedGain: Int, direction), lightNote: String?)`. The
  sentence is one physical change: derived from the optimizer's best candidate when its gain ≥ 3,
  else from the top improvement's recommendation. Unit-tested wording for every direction.
- `app/ui/review`: the card per `PRODUCT.md` section "After the shot" (ring + verdict, worked,
  try-next-time, better crop, what if, details, actions).

## 0b. Easy / Coach defaults and plain-language intents

- `settings`: `experience_mode` (EASY default, COACH), `pro_controls` (off). Defaults per the table in
  `PRODUCT.md`. `SceneIntent.label` stays for code; the UI maps to the plain words.
- `app/ui`: mode strip labels; Settings reorganised: Experience (Easy/Coach), Looks, Pro switch, then
  the rest hidden unless Pro.

## 1. Composition crops on review

- `composition`: `CropSuggester.suggest(frame: FrameAnalysis, scene, intent): List<CropSuggestion>`.
  Candidates: (a) the optimizer's best simulated reframe expressed as a crop (pan = shift, zoom =
  tighten), (b) thirds-aligned crop that puts the primary anchor on the nearest intersection at 85 %
  and 70 % size, (c) centred crop for symmetric scenes, (d) 4:5 and 1:1 portrait crops for a face
  subject. Each candidate is evaluated by transforming geometry with `FrameTransform` into the crop's
  coordinate space and re-running the engine; keep the top 3 with predicted score > current + 3.
  `CropSuggestion(rect: NormalizedRect, aspect: String, predictedScore: Int, headline: String)`.
  Tests: a right-edge subject yields a crop whose predicted score beats the original; a centred
  symmetric scene yields no thirds crop.
- `app/ui/review`: a "Crops" row of 3 thumbnails under the photo (rendered by clipping the same
  bitmap), each labelled with its predicted score; tapping previews it full-size with a dashed outline
  on the original; "Save crop" writes a copy via `exportCrop`; original is never modified.

## 2. "What if" comparison

- `composition`: the optimizer already produces `OptimizationResult.best`. Expose a helper that turns
  the best candidate into a `NormalizedRect` crop (`FramingCandidate.asCrop()`), with the caveat that
  a simulated pan is approximated by shifting the crop window.
- `app/ui/review`: a "What if" chip; while pressed, the photo shows the best-candidate crop with its
  predicted score and the original's score side by side ("68 → 81"). Release returns to the original.

## 3. Light coaching: done in Wave 1 (`LightAnalyzer`).

## 4. Group shot assist

- `vision`: enable ML Kit face classification (`CLASSIFICATION_MODE_ALL`) only when the scene intent
  is GROUP_PORTRAIT or when ≥ 2 faces have been present for 1 s; map `leftEyeOpenProbability` /
  `rightEyeOpenProbability` into `DetectedFace.eyesOpenProbability: Float?` (additive, in
  `composition/model/Detections.kt`).
- `composition`: `GroupReadiness` helper: all faces ≥ 6 % height, none cut at an edge, all eyes-open
  probability ≥ 0.6 (or unknown), no HIGH severity. Exposed on `CompositionResult.groupReady: Boolean?`
  (null when not a group scene).
- `app`: shoot-at-peak uses `groupReady` instead of `isShootReady` in group scenes; the readiness arc
  gains small dots (one per face) that fill as each face becomes ready. Chip "Eyes closed" (one person)
  or "Someone's cut off" as appropriate.

## 5. Heads-up guidance

- `app/ui/camera/HeadsUpGuidance`: optional, Settings > More > "Guide by vibration" and "Guide by
  voice" (both off by default).
  - Vibration: distinct short patterns per direction (left: one pulse; right: two; up: one long; down:
    two long; rotate: three short), emitted when the headline cue changes and every 2.5 s while it
    persists; a single strong pulse on shoot-ready. Uses `VibratorManager` effects; respects
    `Settings.System` haptic feedback off.
  - Voice: on-device `TextToSpeech` (`QUEUE_FLUSH`, speech rate 1.1, no network), speaks the cue chip
    text when it changes ("slightly right", "hold still", "shoot"); silent while the system is in
    do-not-disturb or media is playing (`AudioManager.isMusicActive`).
  - Never both at the full rate: when both are on, voice carries the words and vibration only marks
    shoot-ready.

## 6. Re-shoot ghost

- `app`: after a capture, the review card offers "Shoot again"; returning to the camera draws the
  previous photo's subject outline (mask bounds or face box from `stillAnalysis`) as a 1 dp dashed
  40 % white silhouette for 20 s or until the next capture, so the next frame can be lined up.
  Toggle off with a tap on the ghost. Also available from the gallery thumbnail long-press ("Use as
  guide").

## 7. The coach learns you (and "Your eye")

- `app`: a local `PhotoJournal` (JSON lines in app storage, one record per capture: timestamp, score,
  verdict, scene, intent, recipe, the cue showing at capture, kept/retaken, crop chosen, favourite),
  capped at 2000 records; feeds `AdaptiveWeights` and the "Your eye" screen (tendencies, follow
  rates, best three). One-tap reset clears both.

- `composition`: `AdaptiveWeights` keeps per-category multipliers (0.6..1.4) persisted by the app.
  Feedback events: `FOLLOWED(category)` when the headline cue's issue resolved within 6 s while the
  cue was shown; `IGNORED(category)` when the user captured with the cue still showing; `KEPT` /
  `RETAKEN` on review. Update rule: a small step (±0.03) per event toward "less weight for categories
  you consistently ignore and still keep the photo", bounded, decaying slowly toward 1.0 over 200
  events. Applied by `ScoreAggregator` and `RecommendationEngine` ranking. Pure, unit-tested, and
  explainable: a "Reset coaching preferences" row in Settings > More, plus a one-line summary there
  ("You tend to prefer centred subjects").
- `app`: `CoachFeedbackTracker` in the ViewModel derives the events from state transitions; persisted
  in DataStore (`adaptive_weights` as a compact string).

## 9. Outcomes and the capture pipelines behind them

- Contract (integrator): `Outcome` enum (ANYTHING, PORTRAIT, GROUP, LANDSCAPE, NIGHT, LIGHT_TRAILS,
  SILKY_WATER, CLEAR_CROWD, FOOD) with an `OutcomeSpec` per value: `sceneIntent`, preferred
  `CaptureEnhancement` list in order (e.g. PORTRAIT: BOKEH, AUTO, OFF), `pipeline` (SINGLE,
  BURST_SHARPEST, MOTION_AVERAGE, MOTION_LIGHTEN, MOTION_MEDIAN, FOCUS_STACK), `holdSeconds`,
  `postProcess` (NONE, BOKEH_MASK), `instruction` (the chip text while holding), and `coachingFocus`
  (categories to weight up). Persisted as `outcome` in settings; the old `scene_intent` is derived
  from it.
- `app/ui`: the strip above the shutter lists outcomes in plain words (replaces the scene strip); a
  selected outcome's instruction is the chip while a hold is in progress; the live accumulation
  preview with a progress ring for Motion outcomes.
- `app/camera`: `CaptureEnhancement.BOKEH` (additive) mapped to `ExtensionMode.BOKEH`; `FocusStacker`
  (three captures at near/mid/far focus distances via Camera2Interop `LENS_FOCUS_DISTANCE`, merged by
  local Laplacian energy on a downscaled guide, full-res blend) for LANDSCAPE when the lens supports
  manual focus; falls back to single capture.
- `vision`/`app/camera`: **Motion** capture (LIGHT_TRAILS, SILKY_WATER, CLEAR_CROWD): frames from a higher-resolution analysis stream
  (~1280×960 YUV) accumulated for 2-4 s with gyro-based alignment (rotation homography), blend
  Average (Silky water), Lighten (Light trails) or Median (Clear the crowd); live accumulation shown in
  the preview with a progress ring; abort on heavy motion ("Too shaky, try again"); save the stack plus
  the first frame. Pure `FrameAccumulator` unit-tested on synthetic frames (average of constants, lighten
  of a moving bright dot, median removes a transient).

## 10. Selfie, timer, "Not helpful", card frequency, launch hygiene

- `app`: SELFIE outcome (front lens, mirror-aware chip wording: a cue to move the camera left means
  the on-screen image shifts the same way the user sees it; verify against the mirrored preview),
  countdown 3/10 s timer available on every outcome from a small clock icon in the top strip; in GROUP
  the timer arms readiness instead of counting blindly.
- `app/ui/review`: swipe-to-dismiss (= Keep), "Not helpful" tap on Try-next-time, setting
  `review_card_mode` (ALWAYS, ONLY_WHEN_USEFUL).
- `app`: shutter sound via `MediaActionSound` only when `CameraManager`'s characteristics / region
  policy report it as mandatory (`audio` restriction on `Settings.Global`/`CameraManager` where
  available; otherwise off), OSS licences screen (plain list, no network), all chip and cue strings in
  `strings.xml`, "Send feedback" share composer in About, per-outcome unavailability copy.

## 8. Background separation on review

- `app/camera`: `BackgroundSeparator.apply(bitmap, mask: SubjectMask, strength)`: feathered mask
  upscaled to the bitmap, background darkened by 0.75 and blurred with a fast box blur (two passes,
  radius ∝ 1 % of the long edge) on a half-resolution copy, recomposited. Only offered when
  `stillAnalysis.subjectMask` exists and covers 8–70 % of the frame.
- `app/ui/review`: a "Portrait" chip that previews it (press-and-hold to compare) and "Save as copy".
  Never automatic, never destructive.

## Ownership for the Wave 2 agents

| Agent | Owns | Delivers |
|---|---|---|
| Capture and still analysis | `vision/**`, `app/camera/**`, `app/di/**` | Foundation, 9 (Motion capture, Bokeh extension, focus stack), 4 (vision part), 8 (processing) |
| Engine | `composition/**` | 0 (AfterActionReport), 1 (CropSuggester), 2 helper, 4 (GroupReadiness), 7 (AdaptiveWeights) |
| UI | `app/ui/**`, `app/res/**` | 0 (card), 0b (settings), 1, 2, 7 ("Your eye"), 9 (outcome strip, hold preview), 10 (selfie cues, timer, not-helpful, card mode, licences, feedback, strings), 4 (arc dots), 5, 6, 8 (chips) |
| State and persistence | `CameraViewModel.kt`, `CameraUiState.kt`, `settings/**` | 0b defaults, PhotoJournal, feedback tracker ("Not helpful" included), adaptive weights persistence, outcome and timer state, shutter-sound policy, still-analysis orchestration |

The integrator writes the cross-module contract (new fields with defaults) before launch, as in Wave 1.
