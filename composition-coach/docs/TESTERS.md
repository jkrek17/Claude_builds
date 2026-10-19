# SceneRight: tester guide

Thanks for testing. The app is an Android camera that coaches your framing in real time. Everything
runs on your phone; no photos or camera frames leave the device.

## Install (Android 8.0 or newer)

1. On your phone, open the download link you were sent and tap the `.apk` file.
2. When the download finishes, open it. Android asks to allow installs from this source (Chrome,
   Files, Gmail...). Allow it once.
3. If Google Play Protect says "App blocked" or "unknown developer": tap **More details**, then
   **Install anyway**. If there is no such option: Play Store > your profile picture > Play Protect >
   gear icon > turn off "Scan apps with Play Protect", install, then turn it back on.
4. Open **SceneRight** and allow the camera.

To update, install the newer `.apk` the same way; it replaces the old one and keeps your settings.

**Composition Coach (and the short-lived Eyebright and JustRight builds) is now SceneRight.** Same app: install the new build over the old one and your settings stay. The icon and name change; nothing else does.

## How to use it

- **Pick what you want to shoot** on the strip above the shutter: Anything, A person, A group, A view, Night,
  Light trails, Silky water, Clear the crowd, Food & things, Selfie. Swipe or tap. The app sets everything
  else up for that photo. If your phone can't do part of it, a short note says so once.
- **Point and follow the one cue.** A chevron on the edge you should move toward, brackets to step closer or
  back, a bubble level when you're tilted, with a one-to-three-word label. The ring around the shutter fills
  and turns green when the framing is good.
- **Tap the shutter.** It waits a moment (up to 4 seconds) for good framing and a steady hand, then shoots.
  Hold the shutter to shoot straight away.
- **Some photos ask you to hold still.** Night, Light trails, Silky water and Clear the crowd show "Hold still"
  and a ring that fills while you keep the phone steady. If you shake too much it says "Too shaky, try again";
  your normal photo is still saved.
- **After the shot you get a card.** A verdict (Keeper, Good or Try again), up to three things that worked, and
  one thing to try next time. Sometimes it offers a better crop (saved as a copy, your original stays), a
  "What if" to hold and compare, or a "Portrait" look that softens the background. Swipe down or press back to
  keep the photo; Retake deletes it. If a tip was wrong, tap "Not helpful".
- **Settings > Experience**: Easy (default) or Coach, which adds the score number and a short reason under
  each cue. Settings > Your eye shows what the app has learned about your taste, and Start fresh forgets it.

## What to compare against the stock camera

Take the same scene with SceneRight and with your phone's own camera, then zoom to 100 % on both. Each outcome
saves a normal first frame next to the merged photo (the review's **Compare** shows it), so you can also compare
the two SceneRight files directly.

- **Anything, A person, Selfie:** shadows and dark walls. The merge should be cleaner (less speckle) with the same
  sharpness; check that anything that moved (hands, hair, leaves) is not smeared or ghosted.
- **A group:** shoot a group that blinks or looks away. Faces should come from the frame where they look best;
  check the edges around a swapped face. If it says the photo was kept as shot, that is fine.
- **A view:** a bright sky over a dark foreground. Look for sky detail, shadow detail and halos around the
  horizon, and (on phones that can) near and far both sharp.
- **Night:** a dim room or street, hands only, no tripod. Compare noise, blur and how bright it ends up with
  the stock Night mode.
- **Food & things:** a plate or object a hand away. The near edge and the far edge should both be sharp.
- **Light trails, Silky water, Clear the crowd:** hold still for the full ring; it may run longer than the
  minimum until the effect has settled. Compare with the stock long exposure or a person-removal mode.

The merges take a few seconds after the shutter. The review shows the first frame until the merged photo lands.
When a merge did not work, a short note on the review says why (a warm phone, frames that did not line up, low
memory) and the photo is the normal first frame.

**Pro > Multi-frame merges.** Turn on Pro controls in Settings; "Multi-frame merges" is on by default. Off saves
a single (sharpest-of-burst) photo for the everyday outcomes, which is the fairest comparison with the stock
camera. Say in your feedback which way it was set. Find the capture details with
`adb logcat -s CaptureDiagnostics` (`pipeline=`, `stacked=`, `degraded=`).

## What we want to know

- Did the advice make sense, and did following it make the shot look better?
- Did you read the card after the shot? Was "Try next time" right, and did the next photo come out better?
- Did the outcome you picked give you the photo you wanted (especially Night and the hold-still ones)?
- Was anything confusing, too busy, or too quick to change?
- Did it ever say something obviously wrong (level when tilted, "move right" when you should move
  left, a stranger treated as the subject)?
- Holding the phone sideways: did the icons and labels face you? Did the saved photo come out upright?
- Any crash: reopen the app; a "The app crashed last time" screen appears with a **Copy** button.
  Paste that text into your feedback.

Feedback: a sentence or two plus a screenshot (power + volume down) is perfect.
