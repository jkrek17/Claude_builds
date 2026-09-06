package com.compositioncoach.app.ui.review

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.theme.Background
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import kotlinx.coroutines.launch

/**
 * The photo just taken, letterboxed on black, with [ReviewCard] beneath it and the three actions the spec
 * gives this screen: Retake (outlined, deletes and returns), Share (icon), Keep (filled).
 *
 * The system back gesture and the top-left arrow both mean **Keep** — a captured photo is never silently
 * lost by backing out of this screen.
 */
@Composable
fun ReviewScreen(
    entry: ReviewEntry?,
    captureRepository: CaptureRepository,
    onKeep: () -> Unit,
    onRetakeComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isDeleting by remember { mutableStateOf(false) }

    BackHandler(enabled = entry != null, onBack = onKeep)

    Scaffold(containerColor = Background) { padding ->
        if (entry == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No photo to review", color = OnScrimMuted)
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The saved photo may be landscape as well as portrait (capture is rotation-aware), so the
                // bounding box's aspect ratio follows the decoded image rather than a hardcoded 3:4 —
                // otherwise a landscape photo would be letterboxed down to a sliver. Falls back to 3:4
                // until the image reports a real size.
                val painter = rememberAsyncImagePainter(entry.photoUri)
                val intrinsic = painter.intrinsicSize
                val photoAspectRatio = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                    intrinsic.width / intrinsic.height
                } else {
                    3f / 4f
                }
                Image(
                    painter = painter,
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(photoAspectRatio),
                )
                IconButton(onClick = onKeep, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Keep photo and go back", tint = OnScrim)
                }
            }

            ReviewCard(result = entry.result)

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !isDeleting,
                    onClick = {
                        isDeleting = true
                        scope.launch {
                            captureRepository.delete(entry.photoUri)
                            onRetakeComplete()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Retake") }

                IconButton(onClick = { sharePhoto(context, entry) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share photo", tint = OnScrim)
                }

                Button(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("Keep") }
            }
        }
    }
}

private fun sharePhoto(context: Context, entry: ReviewEntry) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, entry.photoUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share photo")) }
}
