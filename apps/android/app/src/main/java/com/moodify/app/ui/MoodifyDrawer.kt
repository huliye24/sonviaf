package com.moodify.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal const val DESTINATION_PLAYER = -1
internal const val DESTINATION_PLAYLISTS = 0
internal const val DESTINATION_FAVOURITES = 1
internal const val DESTINATION_PROFILE = 2
internal const val DESTINATION_SAVED = 3

private data class DrawerItem(val icon: ImageVector, val label: String, val destination: Int)

@Composable
fun MoodifyDrawerContent(selected: Int, onDestination: (Int) -> Unit) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(.30f),
        drawerContainerColor = Color(0xFFFAF9F5),
        drawerContentColor = Color(0xFF17151A),
        drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                listOf(
                    DrawerItem(Icons.Outlined.FavoriteBorder, "喜欢", DESTINATION_FAVOURITES),
                    DrawerItem(Icons.Outlined.BookmarkBorder, "收藏", DESTINATION_SAVED),
                    DrawerItem(Icons.Outlined.LibraryMusic, "歌单", DESTINATION_PLAYLISTS),
                ).forEach { item ->
                    Box(
                        Modifier.size(58.dp).background(
                            if (selected == item.destination) Color(0xFFFFE3DB) else Color.White,
                            CircleShape,
                        ).clickable { onDestination(item.destination) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, contentDescription = item.label, tint = Color(0xFF17151A), modifier = Modifier.size(27.dp))
                    }
                }
            }
        }
    }
}
