package com.compositioncoach.app.ui.review

import androidx.compose.ui.res.stringResource
import com.compositioncoach.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted

/** Width of the label column in the expanded table, so values line up like an EXIF panel. */
private val LABEL_COLUMN_WIDTH = 116.dp

/**
 * The review card's capture line: one quiet [OnScrimMuted] bodySmall summary ([DiagnosticsFormatter.summary])
 * that expands, on tap, into a two-column table of everything the camera reported. Renders nothing when
 * the camera reported nothing.
 */
@Composable
fun DiagnosticsPanel(
    diagnostics: CaptureDiagnostics,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val summary = DiagnosticsFormatter.summary(diagnostics) ?: return
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val showLabel = stringResource(R.string.diagnostics_show)
    val hideLabel = stringResource(R.string.diagnostics_hide)
    val panelDescription = stringResource(R.string.diagnostics_description, summary)
    val shownState = stringResource(R.string.diagnostics_shown)
    val hiddenState = stringResource(R.string.diagnostics_hidden)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = summary,
            color = OnScrimMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) hideLabel else showLabel,
                ) { expanded = !expanded }
                .clearAndSetSemantics {
                    contentDescription = panelDescription
                    stateDescription = if (expanded) shownState else hiddenState
                }
                .wrapContentHeight(Alignment.CenterVertically),
        )
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = 8.dp)) {
                DiagnosticsFormatter.details(diagnostics).forEach { row -> DetailRow(row) }
            }
        }
    }
}

@Composable
private fun DetailRow(row: DiagnosticsFormatter.Row) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = row.label,
            color = OnScrimMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(LABEL_COLUMN_WIDTH),
        )
        Text(text = row.value, color = OnScrim, style = MaterialTheme.typography.bodyMedium)
    }
}

internal val PreviewDiagnostics = CaptureDiagnostics(
    widthPx = 4000,
    heightPx = 3000,
    iso = 160,
    exposureTimeNs = 8_333_333L,
    aperture = 1.85f,
    focalLengthMm = 6.81f,
    lensFacing = LensFacing.BACK,
    enhancement = CaptureEnhancement.NIGHT,
    captureMode = "MAXIMIZE_QUALITY",
    jpegQuality = 95,
    steadiness = Steadiness.STEADY,
    waitedForSteadyMs = 240,
    zoomRatio = 1f,
    exposureCompensationIndex = 0,
    captureLatencyMs = 412,
)

@Preview(name = "Diagnostics, collapsed", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DiagnosticsPanelPreview() {
    CompositionCoachTheme { DiagnosticsPanel(PreviewDiagnostics, Modifier.padding(horizontal = 20.dp)) }
}

@Preview(name = "Diagnostics, expanded", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DiagnosticsPanelExpandedPreview() {
    CompositionCoachTheme {
        DiagnosticsPanel(PreviewDiagnostics, Modifier.padding(horizontal = 20.dp), initiallyExpanded = true)
    }
}
