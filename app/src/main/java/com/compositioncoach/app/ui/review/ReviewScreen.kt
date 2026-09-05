package com.compositioncoach.app.ui.review

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import kotlinx.coroutines.launch

/**
 * Shows the photo just taken alongside the same score/strengths/improvements the coach saw at capture
 * time ([ReviewEntry.result], evaluated once with [com.compositioncoach.composition.engine.CompositionCoach.evaluateOnce]
 * at COACH level so this screen always has the fullest explanation available).
 */
@Composable
fun ReviewScreen(
    entry: ReviewEntry?,
    captureRepository: CaptureRepository,
    onKeep: () -> Unit,
    onRetakeComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isDeleting by remember { mutableStateOf(false) }

    Scaffold(containerColor = Color.Black) { padding ->
        if (entry == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No photo to review", color = Color.White.copy(alpha = 0.7f))
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Image(
                    painter = rememberAsyncImagePainter(entry.photoUri),
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(onClick = onKeep, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            ReviewDetails(result = entry.result)

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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

                Button(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("Keep") }
            }
        }
    }
}

@Composable
private fun ReviewDetails(result: CompositionResult) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Composition: ${result.score}",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            AssistChip(onClick = {}, label = { Text(result.scene.type.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }

        if (result.strengths.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Strong:", color = Color.White, style = MaterialTheme.typography.labelLarge)
            result.strengths.forEach { BulletLine(it, Color(0xFF34D399)) }
        }

        if (result.improvements.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Could improve:", color = Color.White, style = MaterialTheme.typography.labelLarge)
            result.improvements.forEach { BulletLine(it, Color(0xFFFFC857)) }
        }
    }
}

@Composable
private fun BulletLine(text: String, dotColor: Color) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        Text("•  ", color = dotColor, style = MaterialTheme.typography.bodyMedium)
        Text(text, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
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
            ),
        )
    }
}
