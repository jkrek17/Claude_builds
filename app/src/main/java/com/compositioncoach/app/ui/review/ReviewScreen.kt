package com.compositioncoach.app.ui.review

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.Background
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.SubjectKind
import kotlinx.coroutines.launch

/**
 * Shows the photo just taken full-bleed (letterboxed to 4:3 on black, matching the live preview's own
 * aspect ratio) with a bottom card carrying the same score/strengths/improvements the coach saw at capture
 * time ([ReviewEntry.result], evaluated once with [com.compositioncoach.composition.engine.CompositionCoach.evaluateOnce]
 * at COACH level so this screen always has the fullest explanation available). The system back gesture
 * behaves like the **Keep** button, not a plain "discard and go back" — a captured photo is never silently
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    painter = rememberAsyncImagePainter(entry.photoUri),
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
            }

            ReviewDetails(result = entry.result)

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
                    Icon(Icons.Filled.Share, contentDescription = "Share photo", tint = OnScrim)
                }

                Button(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("Keep") }
            }
        }
    }
}

private fun sharePhoto(context: android.content.Context, entry: ReviewEntry) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, entry.photoUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share photo")) }
}

@Composable
private fun ReviewDetails(result: CompositionResult) {
    val feedback = ReviewFormatter.trim(result.strengths, result.improvements)
    val subjectLine = ReviewFormatter.subjectLine(result.primarySubject)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.score.toString(),
                color = OnScrim,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 34.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(Modifier.weight(1f))
            if (result.intent != SceneIntent.AUTO) {
                AssistChip(onClick = {}, label = { Text("${result.intent.label} mode") })
                Spacer(Modifier.width(8.dp))
            }
            AssistChip(onClick = {}, label = { Text(result.scene.type.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }

        if (subjectLine != null) {
            Text(
                text = subjectLine,
                color = OnScrimMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (feedback.strengths.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            feedback.strengths.forEach { BulletLine(it, Accent.Ready) }
        }

        if (feedback.improvements.isNotEmpty()) {
            Spacer(Modifier.height(if (feedback.strengths.isNotEmpty()) 4.dp else 12.dp))
            feedback.improvements.forEach { BulletLine(it, Accent.Warn) }
        }
    }
}

@Composable
private fun BulletLine(text: String, dotColor: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        Text("•  ", color = dotColor, style = MaterialTheme.typography.bodyMedium)
        Text(text, color = OnScrim.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(name = "Review details", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ReviewDetailsPreview() {
    CompositionCoachTheme {
        ReviewDetails(
            result = CompositionResult.empty().copy(
                score = 86,
                scene = SceneClassification(SceneType.PORTRAIT, confidence = 0.9f),
                strengths = listOf("Level horizon", "Good subject separation"),
                improvements = listOf("Give a little more headroom", "Move the subject off-center"),
                intent = SceneIntent.PORTRAIT,
            ),
        )
    }
}

@Preview(name = "Object subject, trimmed feedback", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ReviewDetailsObjectSubjectPreview() {
    CompositionCoachTheme {
        ReviewDetails(
            result = CompositionResult.empty().copy(
                score = 91,
                scene = SceneClassification(SceneType.OBJECT, confidence = 0.8f),
                primarySubject = DetectedSubject(
                    id = 1,
                    kind = SubjectKind.OBJECT,
                    bounds = NormalizedRect(0.3f, 0.3f, 0.7f, 0.7f),
                ),
                // More than TRIMMED_COUNT of each: only the first two of each show.
                strengths = listOf("Well centered", "Good contrast", "Clean background"),
                improvements = listOf("Move slightly left", "Fill more of the frame"),
            ),
        )
    }
}
