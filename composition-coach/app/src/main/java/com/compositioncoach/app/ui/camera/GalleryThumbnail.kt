package com.compositioncoach.app.ui.camera

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.compositioncoach.app.R
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim

/**
 * What the gallery thumbnail's long-press menu offers: "Your eye" and, once there is a last photo with a
 * subject, "Use as guide" (the re-shoot ghost). A null action is left out of the menu; no actions, no menu.
 */
data class GalleryMenuActions(
    val onOpenYourEye: (() -> Unit)? = null,
    val onUseAsGuide: (() -> Unit)? = null,
) {
    val isEmpty: Boolean get() = onOpenYourEye == null && onUseAsGuide == null
}

/**
 * A 44dp rounded-square thumbnail of [photoUri], the last photo captured. Tap opens it in the photo viewer;
 * long-press opens a small menu ([GalleryMenuActions]). Shows a plain gallery icon until there is a photo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryThumbnail(photoUri: Uri?, onClick: () -> Unit, menu: GalleryMenuActions = GalleryMenuActions()) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val description = stringResource(R.string.gallery_open)
    Box {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Scrim)
                .border(1.dp, OnScrim.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = if (menu.isEmpty) null else stringResource(R.string.gallery_your_eye),
                    onLongClick = if (menu.isEmpty) {
                        null
                    } else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (photoUri != null) {
                Image(
                    painter = rememberAsyncImagePainter(photoUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Icon(imageVector = Icons.Outlined.PhotoLibrary, contentDescription = null, tint = OnScrim.copy(alpha = 0.6f))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menu.onOpenYourEye?.let { open ->
                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_your_eye)) }, onClick = {
                    menuOpen = false
                    open()
                })
            }
            menu.onUseAsGuide?.let { use ->
                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_use_as_guide)) }, onClick = {
                    menuOpen = false
                    use()
                })
            }
        }
    }
}

@Preview(name = "Gallery thumbnail", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GalleryThumbnailPreview() {
    CompositionCoachTheme { GalleryThumbnail(photoUri = null, onClick = {}, menu = GalleryMenuActions(onOpenYourEye = {})) }
}
