package com.compositioncoach.app.ui.youreye

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.compositioncoach.app.R
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnSurface
import com.compositioncoach.app.ui.theme.Surface

private val GUTTER = 20.dp

/**
 * "Your eye": a quiet progress view (`docs/PRODUCT.md`, "Learning what you like"; copy per `docs/BRAND.md`).
 * This month's keepers, three tendencies in plain words, the advice taken most and skipped most, the best three
 * photos, and "Start fresh" (with a confirmation) to reset coaching preferences. No streaks, no badges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YourEyeScreen(data: YourEyeData, onBack: () -> Unit, onReset: () -> Unit, onOpenPhoto: (Uri) -> Unit = {}) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.your_eye_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER)) {
            if (YourEyeFormatter.isEmpty(data)) {
                Text(stringResource(R.string.your_eye_empty), style = MaterialTheme.typography.bodyLarge, color = OnSurface.copy(alpha = 0.8f), modifier = Modifier.padding(top = 24.dp))
            } else {
                YourEyeContent(data, onOpenPhoto)
            }
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.your_eye_private), style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f))
            TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.your_eye_reset)) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.your_eye_reset_confirm_title)) },
            text = { Text(stringResource(R.string.your_eye_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text(stringResource(R.string.your_eye_reset_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.your_eye_reset_confirm_no)) } },
        )
    }
}

@Composable
private fun YourEyeContent(data: YourEyeData, onOpenPhoto: (Uri) -> Unit) {
    Label(stringResource(R.string.your_eye_period))
    Text(
        text = pluralStringResource(R.plurals.your_eye_keepers, data.keepers30d, data.keepers30d),
        style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        color = OnSurface,
    )

    Label(stringResource(R.string.your_eye_tendencies))
    val tendencies = YourEyeFormatter.tendencies(data)
    if (tendencies.isEmpty()) {
        Body(stringResource(R.string.your_eye_no_tendencies))
    } else {
        tendencies.forEach { Body(it) }
    }

    YourEyeFormatter.followedMost(data)?.let { (label, n) ->
        Label(stringResource(R.string.your_eye_follow_most))
        Body(stringResource(R.string.your_eye_follow_rate, label, n))
    }
    YourEyeFormatter.followedLeast(data)?.let { (label, n) ->
        Label(stringResource(R.string.your_eye_follow_least))
        Body(stringResource(R.string.your_eye_follow_rate, label, n))
        Text(stringResource(R.string.your_eye_skip_note), style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.6f))
    }

    val best = YourEyeFormatter.best(data)
    if (best.isNotEmpty()) {
        Label(stringResource(R.string.your_eye_best))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            best.forEach { (uri, score) -> BestPhoto(uri, score, onOpenPhoto, Modifier.weight(1f)) }
            repeat(YourEyeFormatter.MAX_BEST - best.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun BestPhoto(uri: Uri, score: Int, onOpen: (Uri) -> Unit, modifier: Modifier) {
    val description = stringResource(R.string.your_eye_best_description, score)
    TextButton(onClick = { onOpen(uri) }, modifier = modifier.clearAndSetSemantics { contentDescription = description }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).background(Surface)) {
                Image(rememberAsyncImagePainter(uri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(score.toString(), style = MaterialTheme.typography.labelLarge, color = OnSurface, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = OnSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = OnSurface, modifier = Modifier.padding(vertical = 2.dp))
}

internal val SampleYourEye = YourEyeData(
    tendencies = listOf("You like tight, centred portraits", "You like plenty of sky in a view", "You shoot food close and from above"),
    keepers30d = 38,
    followRates = listOf("Closer" to 0.9f, "Level" to 0.8f, "Give looking room" to 0.2f),
    best = listOf(Uri.parse("content://preview/1") to 94, Uri.parse("content://preview/2") to 91, Uri.parse("content://preview/3") to 88),
)

@Preview(name = "Your eye", showBackground = true, heightDp = 800)
@Composable
private fun YourEyePreview() {
    CompositionCoachTheme { YourEyeScreen(data = SampleYourEye, onBack = {}, onReset = {}) }
}

@Preview(name = "Your eye, empty", showBackground = true)
@Composable
private fun YourEyeEmptyPreview() {
    CompositionCoachTheme { YourEyeScreen(data = YourEyeData.EMPTY, onBack = {}, onReset = {}) }
}
