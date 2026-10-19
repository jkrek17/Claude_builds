# Wave 2: signature features

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

## 7. The coach learns you

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
| Still analysis + background | `vision/**`, `app/camera/**`, `app/di/**` | Foundation, 4 (vision part), 8 (processing) |
| Engine | `composition/**` | 1 (CropSuggester), 2 helper, 4 (GroupReadiness), 7 (AdaptiveWeights) |
| Review and camera UI | `app/ui/**`, `app/res/**` | 1, 2, 4 (arc dots), 5, 6, 7 (settings rows), 8 (chips) |
| ViewModel/state | `CameraViewModel.kt`, `CameraUiState.kt`, `settings/**` | Feedback tracker, ghost state, heads-up settings, still-analysis orchestration |

The integrator writes the cross-module contract (new fields with defaults) before launch, as in Wave 1.
