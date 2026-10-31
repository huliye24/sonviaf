package com.moodify.app.ui.screens

import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.moodify.app.data.CatalogueClient
import com.moodify.app.data.Catalogue
import com.moodify.app.data.CatalogueTrack
import com.moodify.app.data.PlaybackManager
import com.moodify.app.data.PlaybackMode
import com.moodify.app.data.PlaybackState
import com.moodify.app.data.QueueItem
import com.moodify.app.R
import com.moodify.app.ui.components.MoodifyMark
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.sin

private const val AUDIO_BASE = "https://rongjingmusic.com/audio/"
private val Night = Color(0xFF000106)
private val Ink = Color(0xFFF8F7FB)
private val Muted = Color(0xFF9798C4)
private val Purple = Color(0xFF8139FF)
private val Blue = Color(0xFF22BCFF)
private val Paper = Color(0xFFFAF9F5)
private val PaperInk = Color(0xFF17151A)
private val PaperMuted = Color(0xFFB7B3B5)

@Composable
fun HomeScreen(
    favouritePaths: Set<String> = emptySet(),
    savedPaths: Set<String> = emptySet(),
    onFavourite: (QueueItem) -> Unit = {},
    onAddToPlaylist: (QueueItem) -> Unit = {},
    onAccount: () -> Unit = {},
    onLikedShortcut: () -> Unit = {},
    onSavedShortcut: () -> Unit = {},
    onPlaylistsShortcut: () -> Unit = {},
) {
    var tracks by remember { mutableStateOf<List<CatalogueTrack>?>(CatalogueClient.internalTestCatalogue().tracks) }
    var loadFailed by remember { mutableStateOf(false) }
    val playback by PlaybackManager.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            PlaybackManager.tick()
            delay(500)
        }
    }
    LaunchedEffect(Unit) {
        PlaybackManager.awaitInitialized()
        val seed = CatalogueClient.internalTestCatalogue()
        if (PlaybackManager.state.value.queue.isEmpty()) PlaybackManager.loadQueue(seed.tracks.toQueue())
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching { CatalogueClient().playableCatalogue() } }
            result.onSuccess { catalogue ->
                val published = catalogue.tracks.distinctBy { it.id }
                tracks = published
                CatalogueClient.cache(context, Catalogue(published))
                val refreshedQueue = published.toQueue()
                if (refreshedQueue.isNotEmpty() &&
                    PlaybackManager.state.value.queue.map { it.path } != refreshedQueue.map { it.path }
                ) {
                    val currentPath = PlaybackManager.state.value.current?.path
                    val refreshedIndex = refreshedQueue.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
                    val restoredPosition = PlaybackManager.state.value.positionMs
                    PlaybackManager.playQueue(refreshedQueue, refreshedIndex, autoPlay = PlaybackManager.state.value.playWhenReady)
                    if (currentPath != null && restoredPosition > 0L) PlaybackManager.seekTo(restoredPosition)
                }
                loadFailed = false
            }.onFailure {
                if (tracks.isNullOrEmpty()) tracks = CatalogueClient.cached(context)?.tracks ?: seed.tracks
                loadFailed = false
            }
            delay(10_000)
        }
    }

    Box(Modifier.fillMaxSize().background(Night)) {
        when {
            tracks == null && !loadFailed -> LoadingSurface()
            loadFailed -> MessageSurface("暂时无法连接曲库")
            tracks.isNullOrEmpty() -> MessageSurface("曲库中还没有可播放作品")
            else -> ArtworkPlayer(
                tracks = tracks.orEmpty(),
                playback = playback,
                favouritePaths = favouritePaths,
                savedPaths = savedPaths,
                onFavourite = onFavourite,
                onAddToPlaylist = onAddToPlaylist,
                onAccount = onAccount,
                onLikedShortcut = onLikedShortcut,
                onSavedShortcut = onSavedShortcut,
                onPlaylistsShortcut = onPlaylistsShortcut,
            )
        }
    }
}

@Composable
private fun ArtworkPlayer(
    tracks: List<CatalogueTrack>,
    playback: PlaybackState,
    favouritePaths: Set<String>,
    savedPaths: Set<String>,
    onFavourite: (QueueItem) -> Unit,
    onAddToPlaylist: (QueueItem) -> Unit,
    onAccount: () -> Unit,
    onLikedShortcut: () -> Unit,
    onSavedShortcut: () -> Unit,
    onPlaylistsShortcut: () -> Unit,
) {
    var libraryOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var rightActionsOpen by remember { mutableStateOf(false) }
    var leftActionsOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }
    var lyricsLines by remember { mutableStateOf<List<String>?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val queue = tracks.toQueue()
    val index = playback.queueIndex.coerceAtLeast(0).coerceAtMost(tracks.lastIndex)
    val pagerState = rememberPagerState(initialPage = index) { tracks.size }
    val logoInteraction = remember { MutableInteractionSource() }
    val context = LocalContext.current

    LaunchedEffect(lyricsOpen, index) {
        if (!lyricsOpen) return@LaunchedEffect
        lyricsLines = null
        val key = tracks[index].lyricsAssetKey
        lyricsLines = if (key == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(URL(AUDIO_BASE + key).readText())
                val lines = json.optJSONArray("lines") ?: return@runCatching emptyList()
                buildList {
                    for (lineIndex in 0 until lines.length()) {
                        lines.optJSONObject(lineIndex)?.optString("text")?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(pagerState, tracks) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page != PlaybackManager.state.value.queueIndex) PlaybackManager.jumpTo(page)
            }
    }
    LaunchedEffect(index) {
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 38.dp, start = 24.dp, end = 24.dp).align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Image(
                    painter = painterResource(R.drawable.local_library_avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable(
                        interactionSource = logoInteraction,
                        indication = null,
                    ) { onAccount() }.semantics { contentDescription = "我的音乐" },
                )
                if (searchOpen) {
                Box(
                    Modifier.weight(1f).padding(start = 18.dp).height(48.dp).clip(RoundedCornerShape(26.dp))
                        .background(Color.White.copy(alpha = .76f)).border(1.dp, Color(0xFFE4E0DC), RoundedCornerShape(26.dp)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it; libraryOpen = it.isNotBlank() },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = PaperInk, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        decorationBox = { inner ->
                            if (searchQuery.isBlank()) Text("⌕", color = Color(0xFF99949B), fontSize = 19.sp)
                            inner()
                        },
                    )
                }
                }
            }

        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().padding(top = 112.dp, bottom = 74.dp),
        ) { page ->
            val pageTrack = tracks[page]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val isCurrent = page == index
                OrangeWaveformTimeline(
                    seed = pageTrack.id,
                    playing = isCurrent && playback.playWhenReady,
                    progress = if (isCurrent && playback.durationMs > 0L) {
                        playback.positionMs.toFloat() / playback.durationMs
                    } else 0f,
                    onSeek = { ratio ->
                        if (!isCurrent) PlaybackManager.jumpTo(page)
                        val duration = if (isCurrent) playback.durationMs else pageTrack.durationMs ?: 0L
                        if (duration > 0L) PlaybackManager.seekTo((duration * ratio).toLong())
                    },
                    modifier = Modifier.fillMaxWidth().height(230.dp).padding(horizontal = 4.dp)
                        .semantics { contentDescription = "${pageTrack.title} 播放时间轴" },
                )
                Text(
                    pageTrack.title,
                    color = PaperInk,
                    fontSize = if (pageTrack.title.length > 18) 25.sp else 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (searchOpen && libraryOpen && searchQuery.isNotBlank()) {
            val matches = tracks.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }.take(5)
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 96.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Color(0xFFE6E1DC), RoundedCornerShape(20.dp))
                    .padding(vertical = 8.dp),
            ) {
                if (matches.isEmpty()) Text("没有匹配作品", color = PaperMuted, modifier = Modifier.padding(20.dp))
                matches.forEach { item ->
                    val trackIndex = tracks.indexOfFirst { it.id == item.id }
                    Text(
                        item.title,
                        color = PaperInk,
                        fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            PlaybackManager.playQueue(queue, trackIndex)
                            libraryOpen = false
                            searchQuery = ""
                        }.padding(horizontal = 20.dp, vertical = 14.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (searchOpen && searchQuery.isBlank()) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 96.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color.White)
                    .border(1.dp, Color(0xFFE6E1DC), RoundedCornerShape(20.dp)).padding(14.dp),
            ) {
                playback.history.take(6).takeIf { it.isNotEmpty() }?.let { history ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近播放", color = Color(0xFF9A9591), fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清空全部记录",
                            tint = Color(0xFF9A9591),
                            modifier = Modifier.size(30.dp).clickable { PlaybackManager.clearHistory() }.padding(7.dp),
                        )
                    }
                    history.forEach { item ->
                        val historyIndex = queue.indexOfFirst { it.path == item.path }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.title,
                                color = PaperInk,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).then(
                                    if (historyIndex >= 0) Modifier.clickable {
                                        PlaybackManager.jumpTo(historyIndex)
                                        searchOpen = false
                                    } else Modifier
                                ).padding(vertical = 9.dp),
                            )
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "删除${item.title}",
                                tint = Color(0xFF9A9591),
                                modifier = Modifier.size(32.dp).clickable { PlaybackManager.removeHistory(item.path) }.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }

        val currentItem = tracks.getOrNull(index)?.queueItem()
        Box(
            Modifier.align(Alignment.BottomCenter).offset(x = (-124).dp).padding(bottom = 23.dp).size(48.dp)
                .clip(CircleShape).clickable {
                    searchOpen = !searchOpen
                    if (!searchOpen) {
                        searchQuery = ""
                        libraryOpen = false
                    }
                }.semantics { contentDescription = if (searchOpen) "收起搜索" else "展开搜索" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = PaperInk, modifier = Modifier.size(24.dp))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).offset(x = (-76).dp).padding(bottom = 23.dp).size(48.dp)
                .clip(CircleShape).clickable { PlaybackManager.setPlaybackMode(playback.playbackMode.next()) }
                .semantics { contentDescription = playback.playbackMode.accessibilityLabel() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = playback.playbackMode.icon(),
                contentDescription = null,
                tint = PaperInk,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).size(58.dp).clip(CircleShape)
                .background(Color(0xFFFCFBF7)).border(1.dp, Color(0xFFD8D2C9), CircleShape)
                .clickable { PlaybackManager.toggle() }
                .semantics { contentDescription = if (playback.playWhenReady) "暂停" else "播放" },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (playback.playWhenReady) "Ⅱ" else "▶", color = PaperInk, fontSize = 18.sp)
        }
        Box(
            Modifier.align(Alignment.BottomCenter).offset(x = 76.dp).padding(bottom = 23.dp).size(48.dp)
                .clip(CircleShape).clickable { currentItem?.let(onFavourite) }
                .semantics { contentDescription = "喜欢" },
            contentAlignment = Alignment.Center,
        ) {
            val liked = currentItem?.path in favouritePaths
            Icon(
                if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = if (liked) Color(0xFFFF5F42) else PaperInk,
                modifier = Modifier.size(24.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).offset(x = 124.dp).padding(bottom = 23.dp).size(48.dp)
                .clip(CircleShape).clickable { currentItem?.let(onAddToPlaylist) }
                .semantics { contentDescription = "收藏" },
            contentAlignment = Alignment.Center,
        ) {
            val saved = currentItem?.path in savedPaths
            Icon(
                if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                tint = if (saved) Color(0xFFFF5F42) else PaperInk,
                modifier = Modifier.size(24.dp),
            )
        }
        if (!leftActionsOpen && !rightActionsOpen && !lyricsOpen) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(28.dp).pointerInput(Unit) {
                    var horizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { horizontalDrag = 0f },
                        onHorizontalDrag = { change, amount ->
                            horizontalDrag += amount
                            change.consume()
                        },
                        onDragEnd = {
                            if (horizontalDrag > 72.dp.toPx()) leftActionsOpen = true
                        },
                        onDragCancel = { horizontalDrag = 0f },
                    )
                },
            )
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp).pointerInput(Unit) {
                    var horizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { horizontalDrag = 0f },
                        onHorizontalDrag = { change, amount ->
                            horizontalDrag += amount
                            change.consume()
                        },
                        onDragEnd = {
                            if (horizontalDrag < -72.dp.toPx()) rightActionsOpen = true
                        },
                        onDragCancel = { horizontalDrag = 0f },
                    )
                },
            )
        }
        if (leftActionsOpen) {
            Box(Modifier.fillMaxSize().clickable { leftActionsOpen = false })
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FloatingActionIcon(Icons.Outlined.FavoriteBorder, "喜欢") {
                    leftActionsOpen = false
                    onLikedShortcut()
                }
                FloatingActionIcon(Icons.Outlined.BookmarkBorder, "收藏") {
                    leftActionsOpen = false
                    onSavedShortcut()
                }
                FloatingActionIcon(Icons.Outlined.LibraryMusic, "歌单") {
                    leftActionsOpen = false
                    onPlaylistsShortcut()
                }
            }
        }
        if (rightActionsOpen) {
            Box(Modifier.fillMaxSize().clickable { rightActionsOpen = false })
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(96.dp).background(Color(0xFFFAF9F5)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(58.dp).background(Color.White, CircleShape).clickable {
                        lyricsOpen = true
                        rightActionsOpen = false
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Lyrics, contentDescription = "歌词", tint = PaperInk, modifier = Modifier.size(27.dp)) }
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier.size(58.dp).background(Color.White, CircleShape).clickable {
                        tracks[index].queueItem()?.let { shareTrack(context, it) }
                        rightActionsOpen = false
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Share, contentDescription = "分享", tint = PaperInk, modifier = Modifier.size(27.dp)) }
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier.size(58.dp).background(Color.White, CircleShape).clickable {
                        tracks[index].queueItem()?.let { downloadTrack(context, it) }
                        rightActionsOpen = false
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.FileDownload, contentDescription = "下载到本机", tint = PaperInk, modifier = Modifier.size(27.dp)) }
            }
        }
        if (lyricsOpen) {
            Column(Modifier.fillMaxSize().background(Paper).padding(horizontal = 30.dp)) {
                Box(
                    Modifier.align(Alignment.End).padding(top = 42.dp).size(48.dp).clickable { lyricsOpen = false },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = "关闭歌词", tint = PaperInk) }
                when {
                    lyricsLines == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFF654B), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                    lyricsLines!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lyrics, contentDescription = "歌词不可用", tint = PaperMuted, modifier = Modifier.size(44.dp))
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 42.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        items(lyricsLines!!) { line ->
                            Text(line, color = PaperInk, fontSize = 22.sp, lineHeight = 32.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(58.dp).background(Color.White, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = PaperInk, modifier = Modifier.size(27.dp))
    }
}

private fun shareTrack(context: android.content.Context, item: QueueItem) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, item.title)
        putExtra(Intent.EXTRA_TEXT, "${item.title}\n${item.path}")
    }
    context.startActivity(Intent.createChooser(send, null))
}

private fun downloadTrack(context: android.content.Context, item: QueueItem) {
    val source = Uri.parse(item.path)
    val extension = source.lastPathSegment?.substringAfterLast('.', "mp3")?.substringBefore('?')?.take(5) ?: "mp3"
    val safeTitle = item.title.replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "Rongjing Music" }
    val request = DownloadManager.Request(source)
        .setTitle(item.title)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "$safeTitle.$extension")
        .setAllowedOverMetered(true)
    (context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
}

private fun PlaybackMode.next(): PlaybackMode = when (this) {
    PlaybackMode.SEQUENTIAL -> PlaybackMode.RANDOM
    PlaybackMode.RANDOM -> PlaybackMode.REPEAT_ONE
    PlaybackMode.REPEAT_ONE -> PlaybackMode.SEQUENTIAL
}

private fun PlaybackMode.icon(): ImageVector = when (this) {
    PlaybackMode.SEQUENTIAL -> Icons.Rounded.Repeat
    PlaybackMode.RANDOM -> Icons.Rounded.Shuffle
    PlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
}

private fun PlaybackMode.accessibilityLabel(): String = when (this) {
    PlaybackMode.SEQUENTIAL -> "顺序播放，点击切换"
    PlaybackMode.RANDOM -> "随机播放，点击切换"
    PlaybackMode.REPEAT_ONE -> "单曲循环，点击切换"
}

@Composable
private fun OrangeWaveformTimeline(
    seed: String,
    playing: Boolean,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberInfiniteTransition(label = "waveform-motion")
    val pulse by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waveform-pulse",
    )
    val seedPhase = (seed.hashCode().toUInt().toLong() % 10_000L) / 713.0
    var draggedProgress by remember(seed) { mutableStateOf<Float?>(null) }
    val safeProgress = (draggedProgress ?: progress).coerceIn(0f, 1f)
    Canvas(
        modifier
            .pointerInput(seed) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                        draggedProgress = ratio
                        onSeek(ratio)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                        draggedProgress = ratio
                        onSeek(ratio)
                    },
                    onDragEnd = { draggedProgress = null },
                    onDragCancel = { draggedProgress = null },
                )
            }
            .pointerInput(seed) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val bars = 84
        val spacing = size.width / bars
        val centerY = size.height / 2f
        val activeScale = if (playing) 0.97f + pulse * 0.03f else 1f
        for (index in 0 until bars) {
            val normalized = index / (bars - 1f)
            val envelope = sin(Math.PI * normalized).toFloat().coerceAtLeast(0f)
            val texture = (
                abs(sin(index * 0.37 + seedPhase)) * 0.46 +
                    abs(sin(index * 0.113 + seedPhase * 0.63)) * 0.34 +
                    abs(sin(index * 0.071 + 1.7)) * 0.20
                ).toFloat()
            val height = size.height * (0.08f + envelope * texture * 0.82f) * activeScale
            val x = spacing * (index + 0.5f)
            drawLine(
                color = if (normalized <= safeProgress) Color(0xFFFF5F42) else Color(0xFFFFB09F),
                start = Offset(x, centerY - height / 2f),
                end = Offset(x, centerY + height / 2f),
                strokeWidth = min(4.dp.toPx(), spacing * 0.48f),
                cap = StrokeCap.Square,
            )
        }
        if (safeProgress > 0f) {
            val playheadX = size.width * safeProgress
            drawLine(
                color = Color(0xFF17151A),
                start = Offset(playheadX, centerY - size.height * .45f),
                end = Offset(playheadX, centerY + size.height * .45f),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

private val galaxyLyrics = listOf(
    6 to "我曾遥望银河星光", 12 to "期待着自己闪耀的模样", 18 to "历经风雨沧桑，终究渡劫成光",
    28 to "沉迷于旧时光亮", 34 to "只会停止向上生长", 40 to "如今亲手清空过往", 46 to "却在原地，失了方向",
    54 to "怕笔端再无惊涛浪", 60 to "怕再也写不出旧时锋芒", 66 to "满心执念成了枷锁", 72 to "困住了想飞翔的渴望",
    81 to "这沉默从不是荒凉", 87 to "是旧羽脱落，新翼生长", 93 to "是灵感在心底悄悄酝酿",
    102 to "不必逼自己即刻绽放", 108 to "不必怕不如从前模样", 114 to "放下执念，心便敞亮",
    120 to "给梦想腾出安放的空间", 126 to "给自己力量，长出新生的翅膀", 136 to "待到清风起",
    140 to "你自会乘风破浪", 144 to "带着更温柔的光", 148 to "飞向更辽阔的诗和远方",
    152 to "自成光芒不再是梦想", 156 to "而是用自己的力量", 160 to "为夜空点亮更多更美的星光",
)

private fun artworkFor(track: CatalogueTrack): String =
    track.coverUrl(AUDIO_BASE, "https://rongjingmusic.com/rongjing-music-logo.svg")

@Composable
private fun PlayerPager(
    tracks: List<CatalogueTrack>,
    playback: PlaybackState,
    favouritePaths: Set<String>,
    onFavourite: (QueueItem) -> Unit,
    onAddToPlaylist: (QueueItem) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = playback.queueIndex.coerceAtLeast(0).coerceAtMost(tracks.lastIndex),
        pageCount = { tracks.size },
    )

    LaunchedEffect(pagerState, tracks) {
        // React as soon as the pager commits to a target instead of waiting for
        // the visual page animation to settle. Media3 can then consume its
        // already-prepared next item while the UI finishes moving.
        snapshotFlow { pagerState.targetPage }
            .distinctUntilChanged()
            .collect { page ->
                if (PlaybackManager.state.value.queue.isEmpty()) PlaybackManager.loadQueue(tracks.toQueue(), page)
                else if (PlaybackManager.state.value.queueIndex != page) PlaybackManager.jumpTo(page)
            }
    }
    LaunchedEffect(playback.queueIndex) {
        val index = playback.queueIndex
        if (index in tracks.indices && index != pagerState.currentPage && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(index)
        }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().semantics {
            contentDescription = "上下滑动切换歌曲"
        },
        beyondViewportPageCount = 1,
        key = { tracks[it].id },
    ) { page ->
        PlayerPage(
            track = tracks[page],
            state = playback,
            active = page == playback.queueIndex,
            favourite = tracks[page].queueItem()?.path in favouritePaths,
            onFavourite = { tracks[page].queueItem()?.let(onFavourite) },
            onAddToPlaylist = { tracks[page].queueItem()?.let(onAddToPlaylist) },
        )
    }
}

@Composable
private fun PlayerPage(
    track: CatalogueTrack,
    state: PlaybackState,
    active: Boolean,
    favourite: Boolean,
    onFavourite: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val playing = active && state.playWhenReady
    val position = if (active) state.positionMs else 0L
    val duration = if (active && state.durationMs > 0) state.durationMs else track.durationMs ?: 0L
    val titleSize = titleFontSize(track.title)
    val glow = Brush.radialGradient(
        colors = listOf(Color(0x332D36CC), Color.Transparent),
        center = Offset(750f, 1250f),
        radius = 820f,
    )

    Box(Modifier.fillMaxSize().background(glow).padding(horizontal = 26.dp)) {
        Brand(Modifier.align(Alignment.TopStart).padding(top = 42.dp))

        Column(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(top = 150.dp)) {
            Text(
                text = track.title,
                color = Ink,
                fontSize = titleSize.sp,
                lineHeight = (titleSize + 5).sp,
                fontFamily = FontFamily.Serif,
                maxLines = 4,
                overflow = TextOverflow.Clip,
            )
            track.playbackMedia()?.let { media ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = media.qualityLabel(),
                    color = Muted,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing) { PlaybackManager.toggle() }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x0FFFFFFF))
                        .clickable(onClick = onFavourite),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (favourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (favourite) "取消收藏" else "收藏",
                        tint = if (favourite) Color(0xFFFF4B66) else Muted,
                        modifier = Modifier.size(25.dp),
                    )
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x0FFFFFFF))
                        .clickable(onClick = onAddToPlaylist),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "加入歌单",
                        tint = Muted,
                        modifier = Modifier.size(27.dp),
                    )
                }
            }
        }

        VinylRecord(
            playing = playing,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 78.dp, y = (-118).dp)
                .size(310.dp),
        )

        Progress(
            positionMs = position,
            durationMs = duration,
            enabled = active,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 35.dp),
        )
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MoodifyMark(Modifier.size(45.dp, 30.dp))
        Text(
            "Sonviaf",
            color = Ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 92.dp, height = 54.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(Purple, Blue)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(31.dp),
        )
    }
}

@Composable
private fun VinylRecord(playing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "record rotation")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(if (playing) rotation else 0f)) {
            val r = min(size.width, size.height) / 2f
            val center = this.center
            drawCircle(Color(0x241E55FF), r, center)
            drawCircle(Color(0x66542DFF), r * .87f, center, style = Stroke(r * .035f))
            drawCircle(Color(0xFF05060C), r * .79f, center)
            for (i in 1..18) {
                drawCircle(
                    color = if (i % 4 == 0) Color(0x445B6491) else Color(0x33212636),
                    radius = r * (.18f + i * .032f),
                    center = center,
                    style = Stroke(1.1f),
                )
            }
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, Color(0x99AAB8F8), Color.Transparent)),
                startAngle = 8f,
                sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(r * .27f, r * .27f),
                size = Size(r * 1.46f, r * 1.46f),
                style = Stroke(r * .11f, cap = StrokeCap.Round),
            )
            drawCircle(Color(0xFF121329), r * .25f, center)
        }
        MoodifyMark(Modifier.size(70.dp, 47.dp).rotate(if (playing) rotation else 0f))
    }
}

@Composable
private fun Progress(positionMs: Long, durationMs: Long, enabled: Boolean, modifier: Modifier = Modifier) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(enabled, safeDuration) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun seek(x: Float) {
                            PlaybackManager.seekTo((safeDuration * (x / size.width).coerceIn(0f, 1f)).toLong())
                        }
                        seek(down.position.x)
                        var change = down
                        while (change.pressed) {
                            val event = awaitPointerEvent()
                            change = event.changes.first()
                            seek(change.position.x)
                            change.consume()
                        }
                    }
                }
        ) {
            val y = size.height / 2f
            val x = size.width * progress
            drawLine(Color(0xFF303442), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(
                Brush.horizontalGradient(listOf(Purple, Blue)),
                Offset(0f, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round,
            )
            drawCircle(Blue, 6.dp.toPx(), Offset(x, y))
            drawCircle(Color.White.copy(alpha = .35f), 2.dp.toPx(), Offset(x, y))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionMs), color = Muted, fontSize = 14.sp)
            Text(formatTime(durationMs), color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun LoadingSurface() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Purple)
    }
}

@Composable
private fun MessageSurface(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Muted, fontSize = 15.sp)
    }
}

private fun CatalogueTrack.queueItem(): QueueItem? = audioAssetKey?.let { key ->
    QueueItem(title = title, subtitle = creatorHandle ?: "荣景音乐", path = audioUrl(AUDIO_BASE) ?: return null)
}

private fun List<CatalogueTrack>.toQueue(): List<QueueItem> = mapNotNull(CatalogueTrack::queueItem)

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun titleFontSize(title: String): Int = when {
    title.length <= 22 -> 44
    title.length <= 34 -> 38
    else -> 34
}
