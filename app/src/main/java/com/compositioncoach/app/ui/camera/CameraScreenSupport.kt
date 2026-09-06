package com.compositioncoach.app.ui.camera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Opens [uri] in the platform's photo viewer; a no-op until the first photo has been captured this run. */
internal fun openGallery(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

/** Sets `FLAG_KEEP_SCREEN_ON` for as long as this composable stays in composition (i.e. the camera screen is showing). */
@Composable
internal fun KeepScreenOn() {
    // LocalActivity (not a manual `LocalContext.current as? Activity` cast) is the lint-clean way to
    // reach the hosting Activity from Compose — a Context is not always an Activity.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
