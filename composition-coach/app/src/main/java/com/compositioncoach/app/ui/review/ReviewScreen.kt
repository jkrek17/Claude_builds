package com.compositioncoach.app.ui.review

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.R
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.outcome.ReviewCardMode
import com.compositioncoach.app.ui.camera.rememberAnimationsEnabled
import com.compositioncoach.app.ui.theme.Background
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.NormalizedRect
import kotlinx.coroutines.launch

/** How far the photo must be dragged down before letting go dismisses the card (= Keep). */
private val SWIPE_TO_KEEP = 120.dp

/**
 * Where the review screen's decisions go. Keep, Retake and Shoot again all leave the screen; the rest stay.
 * [onShootAgain] keeps the photo and returns to the camera with the subject outline as a ghost.
 */
data class ReviewActions(
    val onKeep: () -> Unit,
    val onRetakeComplete: () -> Unit,
    val onNotHelpful: () -> Unit = {},
    val onCropChosen: (CropSuggestion) -> Unit = {},
    val onShootAgain: (NormalizedRect?) -> Unit = {},
)

/**
 * The review screen: the photo just taken, letterboxed on black, the after-action card ([AfterActionCard])
 * beneath it, and Retake / Share / Keep. **Keep is the default:** the back gesture, the top-left arrow and a
 * downward swipe on the photo all mean Keep, so a photo is never lost by backing out.
 *
 * [cardMode] is `ReviewCardMode`: ALWAYS shows the card on every shot; ONLY_WHEN_USEFUL only once there is a
 * Try-next-time or a crop ([AfterActionLogic.showCard]). Retake deletes through [captureRepository] first;
 * "Save as copy" of the processed portrait writes a new gallery photo through it, never touching the original.
 */
@Composable
fun ReviewScreen(
    entry: ReviewEntry?,
    captureRepository: CaptureRepository,
    actions: ReviewActions,
    cardMode: ReviewCardMode = ReviewCardMode.ALWAYS,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isDeleting by remember { mutableStateOf(false) }
    var view by remember(entry?.photoUri) { mutableStateOf(CardViewState()) }
    val animationsEnabled = rememberAnimationsEnabled()

    BackHandler(enabled = entry != null, onBack = actions.onKeep)

    Scaffold(containerColor = Background) { padding ->
        if (entry == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.card_no_photo), color = OnScrimMuted)
            }
            return@Scaffold
        }
        val photo = rememberReviewPhoto(entry)

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SwipeToKeep(onKeep = actions.onKeep, modifier = Modifier.weight(1.3f).fillMaxWidth()) {
                ReviewPhotoArea(entry = entry, photo = photo, view = view, onKeep = actions.onKeep)
            }

            if (AfterActionLogic.showCard(cardMode, entry)) {
                AfterActionCard(
                    entry = entry,
                    painter = photo.painter,
                    photoAspect = photo.aspect,
                    view = view,
                    animationsEnabled = animationsEnabled,
                    callbacks = CardCallbacks(
                        onViewChange = { view = it },
                        onSaveCrop = { crop ->
                            actions.onCropChosen(crop)
                            view = view.copy(selectedCrop = null, savedCopy = true)
                        },
                        onSaveProcessedCopy = {
                            entry.processedPreviewUri?.let { processed ->
                                scope.launch {
                                    runCatching { captureRepository.saveJpeg(captureRepository.readBytes(processed), 0, false) }
                                        .onSuccess { view = view.copy(savedCopy = true) }
                                }
                            }
                        },
                        onNotHelpful = actions.onNotHelpful,
                        onShootAgain = { actions.onShootAgain(AfterActionLogic.resultOf(entry).primarySubject?.bounds) },
                    ),
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !isDeleting,
                    onClick = {
                        isDeleting = true
                        scope.launch {
                            captureRepository.delete(entry.photoUri)
                            actions.onRetakeComplete()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.card_retake)) }

                val shareLabel = stringResource(R.string.card_share_chooser)
                IconButton(onClick = { sharePhoto(context, entry.photoUri, shareLabel) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.card_share), tint = OnScrim)
                }

                Button(onClick = actions.onKeep, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.card_keep)) }
            }
        }
    }
}

/** A downward drag that follows the finger and, past [SWIPE_TO_KEEP], dismisses the card as Keep. */
@Composable
private fun SwipeToKeep(onKeep: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val thresholdPx = with(LocalDensity.current) { SWIPE_TO_KEEP.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (dragging) drag else 0f, tween(if (dragging) 0 else 200), label = "swipeKeep")
    Box(
        modifier = modifier
            .graphicsLayer {
                translationY = shown
                alpha = 1f - (shown / (thresholdPx * 3f)).coerceIn(0f, 0.5f)
            }
            .pointerInput(onKeep) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        if (drag >= thresholdPx) onKeep()
                        dragging = false
                        drag = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        drag = 0f
                    },
                ) { _, amount -> drag = (drag + amount).coerceAtLeast(0f) }
            },
    ) { content() }
}

internal fun sharePhoto(context: Context, uri: Uri, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
}
