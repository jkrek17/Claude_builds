# Releasing Composition Coach

This covers cutting a signed release build, what CI does with it, and the manual Play Console steps
only the account owner can do.

## 1. Versioning

`app/build.gradle.kts` derives the version automatically so you rarely need to set anything by hand:

| Field | Source | Default |
|---|---|---|
| `versionCode` | `CC_VERSION_CODE` env var | `git rev-list --count HEAD` (falls back to `1` if git isn't available) |
| `versionName` | `CC_VERSION_NAME` env var | `"0.9.<versionCode>"` |

The default naming rule is unit-tested in `app/src/test/java/com/compositioncoach/app/BuildInfoTest.kt`
(`BuildInfo.defaultVersionName`) — mirrored logic, since Gradle build scripts aren't covered by
`:app:testDebugUnitTest` directly.

To cut a specific version, either:
- Do nothing — every commit to `main` gets a monotonically increasing `versionCode` for free, or
- Set `CC_VERSION_CODE` / `CC_VERSION_NAME` explicitly (e.g. as workflow env vars for a tagged release
  build) if you want a specific Play Console version number (for example, aligning with a marketing
  version like `1.0.0`).

The current version and build number are shown in-app at **Settings → About**.

## 2. Creating a real upload/signing key (one-time, per app)

Composition Coach is not yet signed with a real upload key — `assembleRelease`/`bundleRelease` fall
back to the checked-in debug keystore (`keystore/debug.keystore`) when the four release-signing env
vars below aren't set, specifically so the build never breaks, but **a debug-keystore-signed AAB
cannot be uploaded to Play Console** (Play requires app signing by Google, which itself requires a real
upload key the first time you create the app's release track).

Generate one (do this once, then keep the resulting `.jks` file and its passwords somewhere safe — a
password manager or secrets vault, never committed to git):

```bash
keytool -genkeypair -v \
  -keystore composition-coach-upload.jks \
  -alias composition-coach-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` will prompt for a keystore password, a key password (can be the same as the keystore
password), and your name/organization details (used only in the certificate, not shown to users).

Then base64-encode the keystore file, for pasting into a GitHub secret:

```bash
base64 -w0 composition-coach-upload.jks > composition-coach-upload.jks.b64
# (macOS: use `base64 -i composition-coach-upload.jks -o composition-coach-upload.jks.b64` instead)
```

## 3. GitHub secrets (repo Settings → Secrets and variables → Actions)

Add these four repository secrets:

| Secret | Value |
|---|---|
| `CC_RELEASE_KEYSTORE_BASE64` | Contents of `composition-coach-upload.jks.b64` from step 2 |
| `CC_RELEASE_KEYSTORE_PASSWORD` | The keystore password you chose |
| `CC_RELEASE_KEY_ALIAS` | `composition-coach-upload` (or whatever alias you used) |
| `CC_RELEASE_KEY_PASSWORD` | The key password you chose |

`app/build.gradle.kts` decodes `CC_RELEASE_KEYSTORE_BASE64` into `build/release.keystore` at
configuration time and points the `release` signing config at it whenever all four are present; it is
never committed and each CI run decodes it fresh into its own ephemeral `build/` directory.

Once all four secrets exist, the `release` CI job (below) both builds *and* publishes a signed
`release-latest` pre-release automatically. Until then, `assembleRelease`/`bundleRelease` keep working
(debug-keystore fallback) so CI and local builds never break — they just aren't Play-uploadable yet.

## 4. What CI does (`.github/workflows/android.yml`)

On every push:

- **`build` job** (unchanged): unit tests, lint, a debug APK uploaded as a build artifact and published
  to the rolling `debug-latest` pre-release, exactly as before.
- **`release` job** (new, runs after `build` succeeds):
  1. `./gradlew :app:assembleRelease :app:bundleRelease` — always runs, using the real upload key if the
     four secrets above are set, otherwise the debug-keystore fallback.
  2. Uploads `app-release.apk` and `app-release.aab` as workflow artifacts (30-day retention) regardless
     of which key signed them.
  3. **Only when `HAS_RELEASE_KEY == 'true'`** (computed from whether all four secrets are non-empty):
     verifies the signed APK with `apksigner verify`, then publishes it to a second rolling pre-release,
     `release-latest`, the same way `debug-latest` works today (delete-and-recreate the tag on every
     push, `gh release create ... --prerelease`).

`release-latest`'s APK is signed with the real upload key, so **it is not what you upload to Play
Console** for the very first release (Play App Signing wants the *App Bundle*, and for existing apps
your uploads must be signed with the same key Play already has on file) — it exists so testers can
sideload a properly-signed build the same way `debug-latest` works today. The **AAB** workflow artifact
from the same job is what you upload to Play Console.

## 5. Manual Play Console steps (account owner only — cannot be done from this repo)

1. **Create the app** in Play Console (if it doesn't exist yet): app name, default language, app-or-game,
   free-or-paid.
2. **Store listing**: paste the title/short description/full description from `docs/PLAY_LISTING.md`,
   upload the icon/feature graphic/screenshots listed there (marked `[MANUAL]` — they need a real device
   or a design tool, not just this repo).
3. **App content** section:
   - Privacy policy: paste the published URL for `docs/PRIVACY_POLICY.md` (e.g. hosted via GitHub Pages,
     or as a raw GitHub file link).
   - Data safety form: answer exactly as laid out in `docs/PLAY_LISTING.md`'s "Data safety form answers"
     table (short version: nothing is collected or shared).
   - Content rating questionnaire: submit through Play Console; notes in `docs/PLAY_LISTING.md`.
   - Target audience / ads / government apps declarations: standard "general audience, no ads" answers.
4. **Set up an internal testing track** (Testing → Internal testing → Create new release):
   - Upload the `app-release.aab` from the `release` CI job's artifacts (or built locally with
     `./gradlew :app:bundleRelease` once the four secrets/env vars are set locally).
   - The **first** upload to any track establishes your app signing key with Google Play App Signing —
     Play re-signs the AAB with its own key for distribution, and your upload key (step 2) only proves
     you're the legitimate publisher going forward. Keep the upload key safe; losing it means asking
     Google to reset it, which has its own manual process.
   - Add internal testers by email, roll out, and share the opt-in link.
5. **Promote** from internal → closed/open testing → production through Play Console once you're happy,
   following Play's staged rollout percentages if you want a gradual release.

None of step 5's actual button-clicks can be automated from this repository — Play Console requires an
authenticated account action for each of them. What this repo (and CI) automates is everything up to
"here is a signed, tested AAB and the exact text/answers to paste in."

## 6. Local release build (for testing before pushing)

```bash
export ANDROID_HOME=/path/to/android-sdk
export CC_RELEASE_KEYSTORE_BASE64="$(base64 -w0 composition-coach-upload.jks)"
export CC_RELEASE_KEYSTORE_PASSWORD="..."
export CC_RELEASE_KEY_ALIAS="composition-coach-upload"
export CC_RELEASE_KEY_PASSWORD="..."

./gradlew :app:assembleRelease :app:bundleRelease
# -> app/build/outputs/apk/release/app-release.apk
# -> app/build/outputs/bundle/release/app-release.aab

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify app/build/outputs/apk/release/app-release.apk
```

Omit the four `CC_RELEASE_*` env vars to build a debug-keystore-signed release APK/AAB instead (still
R8-shrunk and installable, just not Play-uploadable) — useful for a quick "does R8 break anything"
smoke test without touching real signing material.

R8's shrinking/keep-rule warnings, if any, land in
`app/build/outputs/mapping/release/missing_rules.txt` and `app/build/outputs/mapping/release/mapping.txt`
after a release build — check `missing_rules.txt` for anything unexpected after adding a new dependency.
