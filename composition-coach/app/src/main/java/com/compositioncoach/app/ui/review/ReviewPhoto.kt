package com.compositioncoach.app.ui.review

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.compositioncoach.app.R
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.app.ui.theme.Scrim

private const val LEVELLING_TEXT = "Levelling…"

/** The decoded review photo and its own width / height (3:4 until Coil reports a real size). */
class ReviewPhoto(val painter: Painter, val aspect: Float)

/**
 * The review photo's painter, keyed on [ReviewEntry.revision] (`"$uri#$revision"` for Coil's memory and disk
 * caches): the Uri does not change when auto-level rewrites the file, so without it the cached unlevelled
 * bitmap would stay. The same painter feeds the crop cards, which clip it rather than decode again.
 */
@Composable
fun rememberReviewPhoto(entry: ReviewEntry): ReviewPhoto {
    val context = LocalContext.current
    val request = remember(context, entry.photoUri, entry.revision) { reviewImageRequest(context, entry.photoUri, entry.revision) }
    val painter = rememberAsyncImagePainter(request)
    val intrinsic = painter.intrinsicSize
    val aspect = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) intrinsic.width / intrinsic.height else 3f / 4f
    return ReviewPhoto(painter, aspect)
}

/**
 * The photo area: the photo fitted on black, or, while a card chip asks for it, the "What if" framing with its
 * "72 → 83", the processed portrait, or the tapped crop outlined on the full photo. The back arrow (= Keep),
 * the "Levelling…" line and the Compare chip (when auto-level kept an original) sit over it.
 */
@Composable
fun BoxScope.ReviewPhotoArea(entry: ReviewEntry, photo: ReviewPhoto, view: CardViewState, onKeep: () -> Unit) {
    var showingOriginal by remember(entry.photoUri) { mutableStateOf(false) }
    val whatIf = entry.whatIf
    val whatIfScores = AfterActionLogic.whatIfScores(entry)
    val processed = entry.processedPreviewUri
    val photoDescription = stringResource(R.string.card_photo)

    when {
        view.whatIfShowing && whatIf != null -> {
            ClippedPhoto(photo.painter, whatIf, Modifier.align(Alignment.Center).aspectRatio(CropRender.aspect(whatIf, photo.aspect)))
            whatIfScores?.let { (from, to) -> ScoreChangeChip(stringResource(R.string.card_score_change, from, to)) }
        }
        view.portraitShowing && processed != null -> Image(
            painter = rememberAsyncImagePainter(processed),
            contentDescription = photoDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.Center).aspectRatio(photo.aspect),
        )
        else -> Box(Modifier.align(Alignment.Center).aspectRatio(photo.aspect)) {
            val original = entry.originalPhotoUri
            Image(
                painter = if (showingOriginal && original != null) rememberAsyncImagePainter(original) else photo.painter,
                contentDescription = photoDescription,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            view.selectedCrop?.let { CropOutlineOverlay(it.rect, photo.aspect, Modifier.fillMaxSize()) }
        }
    }

    IconButton(onClick = onKeep, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.card_back_keep), tint = OnScrim)
    }
    if (entry.processing) {
        Text(
            text = LEVELLING_TEXT,
            color = OnScrimMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
    if (entry.originalPhotoUri != null) {
        CompareChip(
            showingOriginal = showingOriginal,
            onShowOriginalChange = { showingOriginal = it },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }
}

/** "72 → 83" over the top of the What-if framing. */
@Composable
private fun BoxScope.ScoreChangeChip(text: String) {
    Text(
        text = text,
        color = OnScrim,
        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Scrim)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * The review photo's Coil request, keyed on [revision] for both caches so a file rewritten in place under the
 * same Uri (auto-level) is decoded again instead of served from cache. Pure key format in [reviewImageCacheKey].
 */
private fun reviewImageRequest(context: Context, uri: Uri, revision: Int): ImageRequest {
    val key = reviewImageCacheKey(uri.toString(), revision)
    return ImageRequest.Builder(context).data(uri).memoryCacheKey(key).diskCacheKey(key).apply {
        // Keep the previous revision on screen while the rewritten file decodes, so the swap does not blink.
        if (revision > 0) placeholderMemoryCacheKey(reviewImageCacheKey(uri.toString(), revision - 1))
    }.build()
}

/** `"$uri#$revision"`: the cache key of one revision of a review photo. */
internal fun reviewImageCacheKey(uri: String, revision: Int): String = "$uri#$revision"
