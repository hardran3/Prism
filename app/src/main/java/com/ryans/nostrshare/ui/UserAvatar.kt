package com.ryans.nostrshare.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ryans.nostrshare.NostrShareApp

@Composable
fun UserAvatar(
    pictureUrl: String?,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val avatarLoader = NostrShareApp.getInstance().avatarImageLoader
    
    Box(modifier = modifier.size(size)) {
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model = pictureUrl,
                contentDescription = contentDescription,
                imageLoader = avatarLoader,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                error = painterResource(Icons.Default.Person, tint)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                tint = tint
            )
        }
    }
}

@Composable
private fun painterResource(image: androidx.compose.ui.graphics.vector.ImageVector, tint: Color): androidx.compose.ui.graphics.painter.Painter {
    return androidx.compose.ui.graphics.vector.rememberVectorPainter(image)
}
