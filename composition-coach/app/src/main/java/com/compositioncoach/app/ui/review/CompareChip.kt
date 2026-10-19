package com.compositioncoach.app.ui.review

import androidx.compose.ui.res.stringResource
import com.compositioncoach.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.Background
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim

/**
 * "Compare": while held, the review photo shows the capture as it was before the app's own corrections
 * (auto-level, clarity), the way an editor's before/after works. [showingOriginal] fills the chip so the
 * state is visible. TalkBack cannot hold, so for it a click toggles instead.
 */
@Composable
fun CompareChip(
    showingOriginal: Boolean,
    onShowOriginalChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(onShowOriginalChange)
    val description = stringResource(R.string.compare_description)
    val showingOriginalState = stringResource(R.string.compare_showing_original)
    val showingEditedState = stringResource(R.string.compare_showing_edited)
    val toggleLabel = stringResource(R.string.compare_toggle)
    val showing by rememberUpdatedState(showingOriginal)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    current(true)
                    tryAwaitRelease()
                    current(false)
                })
            }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                stateDescription = if (showingOriginal) showingOriginalState else showingEditedState
                onClick(label = toggleLabel) {
                    current(!showing)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.compare),
            color = if (showingOriginal) Background else OnScrim,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (showingOriginal) OnScrim else Scrim)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Preview(name = "Compare chip", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CompareChipPreview() {
    CompositionCoachTheme { CompareChip(showingOriginal = false, onShowOriginalChange = {}) }
}

@Preview(name = "Compare chip, held", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CompareChipHeldPreview() {
    CompositionCoachTheme { CompareChip(showingOriginal = true, onShowOriginalChange = {}) }
}
