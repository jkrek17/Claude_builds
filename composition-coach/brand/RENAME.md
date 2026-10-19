# Rename checklist: Composition Coach → Eyebright

For the integrator. Every place in the repo that carries the name, in the order to change them, with
what must **not** change. Line references are as of commit 0505f10.

## The one rule

**Users see the new name; the machine keeps the old one.** Change labels, copy, icons and docs.
Leave package names, the application id, class names and the signing key alone.

| Keep exactly as is | Why |
|---|---|
| `applicationId = "com.compositioncoach.app"` (`app/build.gradle.kts`, line 61) | Android identifies an app by this id. Change it and every tester's phone treats the next build as a different app: it installs side by side, the old one never updates, settings are lost. On Play the id is permanent once the first upload is made. Users never see it. |
| `namespace = "com.compositioncoach.app"` and `com.compositioncoach.vision` | Generates the `R` and `BuildConfig` packages. Renaming means touching every Kotlin file for zero user benefit. |
| Kotlin packages `com.compositioncoach.*`, classes `CompositionCoachApp`, `CompositionCoach` (engine facade), `CompositionCoachTheme`, `Theme.CompositionCoach` | Internal. A big diff that collides with every other agent's work and changes nothing anyone sees. |
| `rootProject.name = "CompositionCoach"` (`settings.gradle.kts`) | Only the Gradle/IDE project name. |
| The signing keys (`keystore/debug.keystore`, the release upload key and its `CC_RELEASE_*` secrets) | A new key means every installed copy must be uninstalled first. Never regenerate a key because of the rename. |
| Release tags `debug-latest`, `release-latest` | Testers have these links bookmarked; the README and tester guide point at them. |

## Step 1: the app (one PR, ships as a normal update)

1. **App label.** `app/src/main/res/values/strings.xml`: `app_name` → `Eyebright`. This is the
   launcher label, the recents title and the system settings name. Copy the other values from
   `brand/strings/strings.xml` section 1 (permission screen, privacy footer, onboarding).
2. **Launcher icon.** Copy `brand/identity/ic_launcher_foreground.xml`,
   `ic_launcher_background.xml` and `ic_launcher_monochrome.xml` over the files of the same name in
   `app/src/main/res/drawable/`. The `mipmap-anydpi-v26/ic_launcher*.xml` wrappers stay as they are.
3. **Hard-coded user-visible strings in Kotlin** (owned by the UI and crash code):
   - `app/.../ui/crash/CrashReportScreen.kt` lines 77 and 83: "Composition Coach crash report" →
     "Eyebright crash report" (clipboard label and email subject).
   - `app/.../crash/CrashLog.kt` line 47: the report header `"Composition Coach $versionName"` →
     `"Eyebright $versionName"`. Optional: add `(com.compositioncoach.app)` so old and new reports are
     easy to match.
   - `app/.../privacy/PrivacyPolicyText.kt`: every "Composition Coach" (lines 19, 28, 44, 46, 50, 52,
     54, and the heading "WHAT COMPOSITION COACH DOES NOT DO" on line 56). Change it in the same release
     as `docs/PRIVACY_POLICY.md` (the policy says the two always ship together).
   - Comments in `AndroidManifest.xml` (line 13) and `ui/theme/*.kt`: optional, cosmetic.
4. **Photo album folder: decide, do not drift.** Photos save to `Pictures/CompositionCoach`
   (`CaptureRepository.kt`, `ALBUM_DIR_NAME`, line 189). Users see this folder name in Google Photos
   and file managers.
   - **Recommended now: leave it.** Changing it splits every tester's photos across two albums, and
     the gallery thumbnail restore (`latestPhotoUri()`, which queries one `RELATIVE_PATH`) stops finding
     older photos.
   - **If changed to `Pictures/Eyebright`:** query both folders in `latestPhotoUri()` for at least one
     release, and update the folder name in `PrivacyPolicyText.kt` (line 36), `docs/PRIVACY_POLICY.md`,
     `README.md` and `app/README.md` (line 423) in the same PR. Existing photos are never moved.
5. **Privacy promise before Wave 2.** `AndroidManifest.xml` has `android:allowBackup="true"`. Before
   the taste model and photo journal ship, set it to `false` or add backup and data-extraction rules that
   exclude them, so "nothing leaves your phone" stays literally true (`docs/BRAND.md`).
6. **Tests.** No test asserts the display name today (`AppLaunchTest`, `CoachingLayerSmokeTest` only
   reference class names, which do not change). Run the usual suite:
   `./gradlew :composition:test :vision:testDebugUnitTest :app:testDebugUnitTest` and lint.

## Step 2: CI and release (same PR or the next)

`.github/workflows/composition-coach.yml` (repo root):

1. Workflow `name:` (line 1) `Composition Coach (Android) CI` → `Eyebright (Android) CI`. Harmless;
   only the Actions tab label changes. Required-check names in branch protection, if any, must be
   updated to match.
2. Release asset file names (lines 69-72, 92, 176-180): `CompositionCoach-debug.apk`,
   `CompositionCoach-debug-arm32.apk`, `CompositionCoach-release-arm64.apk`,
   `CompositionCoach-release-arm32.apk` → `Eyebright-debug.apk`, `Eyebright-debug-arm32.apk`,
   `Eyebright-release-arm64.apk`, `Eyebright-release-arm32.apk`.
   **Risk:** any direct asset URL a tester bookmarked (rather than the release page) stops working.
   Update the same file names in the release notes strings (lines 77, 95, 185), `README.md` and
   `docs/TESTERS.md` in the same commit.
3. Mirror release title (line 94) `Composition Coach (test build)` → `Eyebright (test build)`, and
   the `--notes` texts (lines 77, 95, 185) "launch Composition Coach" → "launch Eyebright".
4. Leave the uploaded artifact names (`composition-coach-debug-apk`, `-release-apk`, `-release-aab`),
   the `paths:` filters, `working-directory: composition-coach` and the secret names (`CC_*`) alone;
   they follow the folder, not the brand.
5. `scripts/make-upload-key.sh` line 16: the `-dname "CN=Composition Coach, O=Composition Coach"` is
   used only when a **new** key is generated. Change it for future keys if you like; it does not
   affect the existing key and is never a reason to make a new one.

## Step 3: documents

In this order, so links never point at text that still uses the old name:

1. `composition-coach/README.md`: title, intro, "Open **Composition Coach**" (install step 5), APK
   file names, `Pictures/CompositionCoach` mentions (only if the folder changed), the CI run name
   ("Composition Coach (Android) CI"). Add a line: "Formerly Composition Coach."
2. Root `README.md` line 8: the project table row name → **Eyebright**, description → "Android camera
   that frames for you and coaches you after the shot, all on-device."
3. `CLAUDE.md` (repo root) line 3: mentions the folder `composition-coach/` only; no change unless
   the folder is renamed.
4. `docs/TESTERS.md`: title, "Open **Eyebright**", APK file names. Tell testers in the release notes:
   *"Composition Coach is now Eyebright. Same app: install this over the old one and your settings
   stay. The icon and name change; nothing else does."*
5. `docs/PRIVACY_POLICY.md`: every mention (title and lines 5, 18, 34, 37, 42, 46, 48, 53, 75), in
   the same release as `PrivacyPolicyText.kt`; then republish the public policy URL.
6. `docs/RELEASE.md` (title, line 29), `docs/APP_UX.md` (title, the permission sentence on line 82
   → the new permission copy from `docs/BRAND.md`), `docs/COACHING_UI.md` (no name today),
   `docs/PRODUCT.md` (line 6: replace "Working name stays..." with "Name: Eyebright (see
   docs/BRAND.md)"), `docs/WAVE2.md` (no name today).
7. `app/README.md` line 1 title, `composition/README.md`: only prose mentions of the product; leave
   references to the `CompositionCoach` class.
8. `docs/PLAY_LISTING.md`: already rewritten for Eyebright.

## Step 4 (optional, later, its own PR): the folder

Renaming `composition-coach/` to `eyebright/` is cosmetic and touches the most places. If done:

- `git mv composition-coach eyebright` in a commit with no other changes.
- Workflow: rename the file, `paths:` filters (lines 7, 9), `working-directory` (line 17), artifact
  `path:` entries (lines 56, 103-105, 154, 162) and every `composition-coach/docs/...` mention in notes.
- Root `README.md` link, `CLAUDE.md` folder list, every relative link in the docs.
- Open PRs from other agents will conflict: do it when nothing else is in flight.

## Step 5: outside the repo [MANUAL]

- Trademark search (US, EU, UK; classes 9 and 42) before spending on anything below.
- Register `eyebright.app` (and `eyebright.camera` if wanted).
- Play Console: app name **Eyebright: Camera Coach**, store listing from `docs/PLAY_LISTING.md`,
  512 px icon and feature graphic exported from `brand/identity/`, privacy policy URL.
- Host `brand/site/index.html` (any static host); replace the two placeholders (download link,
  privacy link).
- Public releases mirror repository (`CC_RELEASES_REPO`): rename its description and README.
