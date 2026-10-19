package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Timer10
import androidx.compose.material.icons.outlined.Timer3
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.R
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim

/** The countdown numerals: big, light, tabular, with a soft shadow-free look over any preview. */
private val CountdownStyle = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum")

/**
 * The clock icon in the top strip: tap cycles Off / 3 s / 10 s ([TimerLogic.next]) through
 * `CameraViewModel.onTimerSelected`. When on, a small label follows the icon: "3 s", or in Group
 * "when everyone's ready", because there the timer hands over to readiness rather than counting blindly.
 */
@Composable
fun TimerButton(
    seconds: Int,
    outcome: Outcome,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (TimerLogic.labelKind(seconds, outcome)) {
        TimerLogic.LabelKind.OFF -> null
        TimerLogic.LabelKind.SECONDS -> stringResource(R.string.timer_seconds, seconds)
        TimerLogic.LabelKind.WHEN_EVERYONE_READY -> stringResource(R.string.timer_group)
    }
    val description = stringResource(R.string.timer_button_description, label ?: stringResource(R.string.timer_off))
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onSelect(TimerLogic.next(seconds)) },
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        ) {
            Icon(
                imageVector = when (seconds) {
                    0 -> Icons.Outlined.TimerOff
                    3 -> Icons.Outlined.Timer3
                    10 -> Icons.Outlined.Timer10
                    else -> Icons.Outlined.Timer
                },
                contentDescription = null,
                tint = OnScrim,
            )
        }
        if (label != null && outcome == Outcome.GROUP) {
            Text(
                text = label,
                color = OnScrim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 6.dp).clearAndSetSemantics { },
            )
        }
    }
}

/**
 * The self-timer's numerals centred over the preview while `CameraUiState.timerCountdown` runs. Rotates in place
 * with the phone so the people in front of it can read it in any hold; TalkBack hears each second.
 */
@Composable
fun BoxScope.TimerCountdownOverlay(
    countdown: Int?,
    deviceRotationDegrees: Int,
    animationsEnabled: Boolean,
) {
    if (countdown == null) return
    val description = stringResource(R.string.timer_countdown_description, countdown)
    RotatedChrome(deviceRotationDegrees = deviceRotationDegrees, modifier = Modifier.align(Alignment.Center)) {
        val fadeMs = motionDuration(Motion.CROSSFADE_MS, animationsEnabled)
        AnimatedContent(
            targetState = countdown,
            transitionSpec = { fadeIn(tween(fadeMs)) togetherWith fadeOut(tween(fadeMs)) },
            label = "timerCountdown",
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Assertive
            },
        ) { value ->
            Text(text = value.toString(), color = OnScrim.copy(alpha = 0.9f), style = CountdownStyle, textAlign = TextAlign.Center)
        }
    }
}

@Preview(name = "Timer button, states", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun TimerButtonPreview() {
    CompositionCoachTheme {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TimerButton(seconds = 0, outcome = Outcome.ANYTHING, onSelect = {})
            TimerButton(seconds = 3, outcome = Outcome.ANYTHING, onSelect = {})
            TimerButton(seconds = 10, outcome = Outcome.GROUP, onSelect = {})
        }
    }
}

@Preview(name = "Timer countdown", showBackground = true, backgroundColor = 0xFF303030, widthDp = 300, heightDp = 400)
@Composable
private fun TimerCountdownPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) { TimerCountdownOverlay(countdown = 3, deviceRotationDegrees = 0, animationsEnabled = false) }
    }
}

@Preview(name = "Timer countdown, rotation 90", showBackground = true, backgroundColor = 0xFF303030, widthDp = 300, heightDp = 400)
@Composable
private fun TimerCountdownRotatedPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) { TimerCountdownOverlay(countdown = 2, deviceRotationDegrees = 90, animationsEnabled = false) }
    }
}

/**
 * Selfie's default 3-second countdown, through the ordinary timer path ([onTimerSelected] =
 * `CameraViewModel.onTimerSelected`): armed when Selfie is picked with the timer off, disarmed on leaving Selfie
 * only if this armed it and the user left it at 3 s. See [TimerLogic.selfieTimer].
 */
@Composable
fun SelfieTimerDefault(outcome: Outcome, timerSeconds: Int, onTimerSelected: (Int) -> Unit) {
    val previous = remember { arrayOfNulls<Outcome>(1) }
    val autoArmed = remember { booleanArrayOf(false) }
    val currentSeconds by rememberUpdatedState(timerSeconds)
    LaunchedEffect(outcome) {
        when (TimerLogic.selfieTimer(previous[0], outcome, currentSeconds, autoArmed[0])) {
            TimerLogic.SelfieTimerAction.ARM -> {
                autoArmed[0] = true
                onTimerSelected(TimerLogic.SELFIE_DEFAULT_SECONDS)
            }
            TimerLogic.SelfieTimerAction.DISARM -> {
                autoArmed[0] = false
                onTimerSelected(0)
            }
            TimerLogic.SelfieTimerAction.NONE -> if (outcome != Outcome.SELFIE) autoArmed[0] = false
        }
        previous[0] = outcome
    }
}
