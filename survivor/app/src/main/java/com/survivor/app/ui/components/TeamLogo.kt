package com.survivor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.survivor.engine.Team

/**
 * ESPN's team logo, crossfaded in. Falls back to a tinted circle with the team's abbreviation while
 * loading or when offline/blocked, so the layout never collapses if the network request fails.
 */
@Composable
fun TeamLogo(team: Team, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = "https://a.espncdn.com/i/teamlogos/nfl/500/${team.abbr.lowercase()}.png"
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context).data(url).crossfade(200).build(),
        contentDescription = "${team.fullName} logo",
        modifier = modifier.size(size),
        loading = { LogoFallback(team, size) },
        error = { LogoFallback(team, size) },
    )
}

@Composable
private fun LogoFallback(team: Team, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            team.abbr,
            fontSize = (size.value * 0.32f).sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
