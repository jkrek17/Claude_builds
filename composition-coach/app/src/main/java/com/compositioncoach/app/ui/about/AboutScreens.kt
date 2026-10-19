package com.compositioncoach.app.ui.about

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.R
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnSurface

/** One open-source component and where it comes from. All of these are Apache License 2.0. */
data class Licence(val name: String, val owner: String)

/** The static list the licences screen shows. No network: the text names the licence and its address. */
object Licences {
    val ALL = listOf(
        Licence("AndroidX CameraX", "The Android Open Source Project"),
        Licence("ML Kit (face, object, segmentation)", "Google LLC"),
        Licence("Jetpack Compose and AndroidX", "The Android Open Source Project"),
        Licence("Coil", "Coil Contributors"),
        Licence("Kotlin and kotlinx.coroutines", "JetBrains s.r.o. and Kotlin contributors"),
    )
}

/** Settings > About > Open-source licences: a plain list, each with its Apache 2.0 line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licences_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.licences_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Licences.ALL.forEach { licence ->
                HorizontalDivider()
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(licence.name, style = MaterialTheme.typography.bodyLarge, color = OnSurface)
                    Text(licence.owner, style = MaterialTheme.typography.bodySmall, color = OnSurface.copy(alpha = 0.7f))
                    Text(
                        "${stringResource(R.string.licence_apache_2)} · ${stringResource(R.string.licence_apache_2_url)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/**
 * "Send feedback" (`docs/PRODUCT.md`, launch hygiene): a plain-text share through the system sheet, carrying the
 * last after-action card and capture diagnostics when there is one. No analytics, no network: the user picks
 * where it goes, or cancels.
 */
object FeedbackComposer {
    fun open(context: Context, body: String) {
        val appName = context.getString(R.string.app_name)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_subject, appName))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.settings_send_feedback))) }
    }
}

@Preview(name = "Licences", showBackground = true)
@Composable
private fun LicencesPreview() {
    CompositionCoachTheme { LicencesScreen(onBack = {}) }
}
