package com.moodify.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * One playable track in the queue.
 * [path] is either an absolute http(s) URL (public audio asset) or a
 * backend API path (resolved as <base>/api/v1<path> with Bearer auth).
 */
data class QueueItem(
    val title: String,
    val subtitle: String = "",
    val path: String,
    val isOriginal: Boolean = false,
    val preset: String = "",
    val mrsDelta: Double? = null,
    val gatePassed: Boolean = false,
)

data class PlaybackState(
    val title: String = "",
    val url: String? = null,
    val playing: Boolean = false,
    /** Stable user intent; unlike [playing], this stays true while a new track buffers. */
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val queue: List<QueueItem> = emptyList(),
    val queueIndex: Int = -1,
    val outputRoute: PlaybackOutputRoute = PlaybackOutputRoute.DEVICE,
    val playbackSuppressed: Boolean = false,
    val buffering: Boolean = false,
    val transitionLatencyMs: Long? = null,
    val offloadRequested: Boolean = true,
    val offloadActive: Boolean = false,
    val sleepingForOffload: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val history: List<QueueItem> = emptyList(),
) {
    val current: QueueItem? get() = queue.getOrNull(queueIndex)
}

/**
 * Media3 playback for the demo: streams processed/original audio from the
 * Moodify backend with the pair token attached (the API requires Bearer
 * auth on every endpoint). Queue-aware for continuous playback.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackManager {

    private var player: ExoPlayer? = null
    private var appContext: Context? = null
    private var session: MediaSession? = null
    private var dataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var audioCache: PlaybackAudioCache? = null
    private var routeMonitor: PlaybackRouteMonitor? = null
    private var snapshotStore: PlaybackSnapshotStore? = null
    private var preferenceStore: PlaybackPreferenceStore? = null
    private var lastSnapshotWriteElapsedMs = 0L
    private var transitionStartedElapsedMs: Long? = null
    private val failedQueueIndices = mutableSetOf<Int>()
    private var baseUrlProvider: (() -> String)? = null
    private var tokenProvider: (() -> String?)? = null
    private var userWantsPlayback = false
    private var artworkUri: Uri? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val initialized = MutableStateFlow(false)

    /** The MediaSession backing [PlaybackService]'s notification/controls. */
    internal val mediaSession: MediaSession?
        get() = session

    fun init(context: Context) {
        if (player != null) return
        appContext = context.applicationContext
        snapshotStore = PlaybackSnapshotStore(context.applicationContext)
        preferenceStore = PlaybackPreferenceStore(context.applicationContext)
        val preferences = preferenceStore!!.read()
        _state.value = _state.value.copy(playbackMode = preferences.mode, history = preferences.history)
        artworkUri = Uri.parse("android.resource://${context.packageName}/drawable/moodify_lockscreen_artwork")
        val tokenStore = TokenStore(context.applicationContext)
        val baseUrlStore = BaseUrlStore(context.applicationContext)
        baseUrlProvider = { baseUrlStore.baseUrl }
        tokenProvider = { tokenStore.token() }
        val factory = DefaultHttpDataSource.Factory().apply {
            refreshAuthHeader()
        }
        dataSourceFactory = factory
        audioCache = PlaybackAudioCache(context.applicationContext, factory)
        routeMonitor = PlaybackRouteMonitor(context.applicationContext) { route ->
            _state.value = _state.value.copy(outputRoute = route)
        }.also(PlaybackRouteMonitor::start)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000, // Keep enough audio ahead for an immediate swipe transition.
                90_000,
                100,
                250,
            )
            .setBackBuffer(5_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        player = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(audioCache!!.dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setAudioOffloadPreferences(
                        TrackSelectionParameters.AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(
                                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                            )
                            .setIsGaplessSupportRequired(true)
                            .build()
                    )
                    .build()
                Log.i("RongjingPlayback", "offload_requested=true gapless_required=true")
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addAudioOffloadListener(object : ExoPlayer.AudioOffloadListener {
                    override fun onOffloadedPlayback(offloadedPlayback: Boolean) {
                        _state.value = _state.value.copy(offloadActive = offloadedPlayback)
                        Log.i("RongjingPlayback", "offload_active=$offloadedPlayback")
                    }

                    override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
                        _state.value = _state.value.copy(sleepingForOffload = sleepingForOffload)
                        Log.i("RongjingPlayback", "offload_sleeping=$sleepingForOffload")
                    }
                })
                repeatMode = Player.REPEAT_MODE_ALL
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.value = _state.value.copy(playing = isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val s = _state.value
                        when (playbackState) {
                            Player.STATE_BUFFERING -> _state.value = s.copy(buffering = true)
                            Player.STATE_READY -> {
                                failedQueueIndices.remove(player?.currentMediaItemIndex)
                                finishTransition()
                                _state.value = _state.value.copy(buffering = false, error = null)
                            }
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        userWantsPlayback = playWhenReady
                        _state.value = _state.value.copy(playWhenReady = playWhenReady)
                        persistSnapshot(force = true)
                    }

                    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                        _state.value = _state.value.copy(
                            playbackSuppressed = playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        recoverFromFailure()
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val s = _state.value
                        if (s.queue.isEmpty()) return
                        // derive queueIndex from the media item's custom key (index)
                        val idx = mediaItem?.mediaId?.toIntOrNull() ?: s.queueIndex
                        val current = s.queue.getOrNull(idx)
                        beginTransition()
                        val history = current?.let { item ->
                            listOf(item) + s.history.filterNot { it.path == item.path }
                        }?.take(50) ?: s.history
                        _state.value = s.copy(
                            queueIndex = idx,
                            title = current?.title.orEmpty(),
                            url = current?.let(::resolveUrl),
                            buffering = true,
                            history = history,
                        )
                        preferenceStore?.writeHistory(history)
                        prefetchAfter(idx, s.queue)
                        persistSnapshot(force = true)
                    }
                })
            }
        session = MediaSession.Builder(context.applicationContext, player!!).build()
        restoreSnapshot()
        initialized.value = true
    }

    /** Suspends startup-dependent work without blocking the app's first rendered frame. */
    suspend fun awaitInitialized() {
        initialized.filter { it }.first()
    }

    /** Load a whole queue and start playing from [startIndex]. */
    fun playQueue(items: List<QueueItem>, startIndex: Int = 0, autoPlay: Boolean = true) {
        val p = player ?: return
        if (items.isEmpty()) return
        val idx = PlaybackQueuePolicy.clampIndex(startIndex, items.size)
        failedQueueIndices.clear()
        beginTransition()
        refreshAuthHeader()
        userWantsPlayback = autoPlay
        if (autoPlay) ensurePlaybackService()
        val previous = _state.value
        _state.value = PlaybackState(
            queue = items,
            queueIndex = idx,
            playWhenReady = autoPlay,
            playbackMode = previous.playbackMode,
            history = previous.history,
            outputRoute = previous.outputRoute,
        )
        p.setMediaItems(items.mapIndexed(::mediaItem), idx, 0L)
        applyPlaybackMode(previous.playbackMode, idx, items)
        p.seekTo(idx, 0L)
        p.playWhenReady = autoPlay
        p.prepare()
        prefetchAfter(idx, items)
        val current = items[idx]
        _state.value = _state.value.copy(
            title = current.title,
            url = resolveUrl(current),
            playing = autoPlay,
            error = null,
        )
        persistSnapshot(force = true)
    }

    /** Populate the public catalogue without starting sound before the user's first Play tap. */
    fun loadQueue(items: List<QueueItem>, startIndex: Int = 0) = playQueue(items, startIndex, autoPlay = false)

    /** Play a single backend endpoint path (kept for simple call sites). */
    fun play(path: String, title: String) {
        playQueue(listOf(QueueItem(title = title, path = path)), 0)
    }

    /** Play audio documents handed to the app by Android's resolver, preserving their order. */
    fun playExternal(items: List<Pair<Uri, String>>) {
        playQueue(
            PlaybackQueuePolicy.externalItems(items.map { (uri, title) -> uri.toString() to title }),
            0,
        )
    }

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        beginTransition()
        player?.seekToNextMediaItem()
        finishTransitionIfAlreadyReady()
    }

    fun jumpTo(index: Int) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val idx = index.coerceIn(0, s.queue.size - 1)
        beginTransition()
        player?.seekToDefaultPosition(idx)
        _state.value = s.copy(
            queueIndex = idx,
            title = s.queue[idx].title,
            url = resolveUrl(s.queue[idx]),
            playWhenReady = userWantsPlayback,
        )
        persistSnapshot(force = true)
        finishTransitionIfAlreadyReady()
    }

    fun previous() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        beginTransition()
        player?.seekToPreviousMediaItem()
        finishTransitionIfAlreadyReady()
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        val s = _state.value
        _state.value = s.copy(playbackMode = mode)
        preferenceStore?.writeMode(mode)
        applyPlaybackMode(mode, s.queueIndex.coerceAtLeast(0), s.queue)
        persistSnapshot(force = true)
    }

    fun removeHistory(path: String) {
        val history = _state.value.history.filterNot { it.path == path }
        _state.value = _state.value.copy(history = history)
        preferenceStore?.writeHistory(history)
    }

    fun clearHistory() {
        _state.value = _state.value.copy(history = emptyList())
        preferenceStore?.writeHistory(emptyList())
    }

    private fun applyPlaybackMode(mode: PlaybackMode, currentIndex: Int, queue: List<QueueItem>) {
        val p = player ?: return
        if (queue.isEmpty()) return
        when (mode) {
            PlaybackMode.SEQUENTIAL -> {
                p.repeatMode = Player.REPEAT_MODE_ALL
                p.shuffleModeEnabled = false
            }
            PlaybackMode.RANDOM -> {
                // Media3 maintains a randomized traversal internally. Replacing its shuffle
                // order here can also replace the current item during process restoration.
                p.repeatMode = Player.REPEAT_MODE_ALL
                p.shuffleModeEnabled = true
            }
            PlaybackMode.REPEAT_ONE -> {
                p.shuffleModeEnabled = false
                p.repeatMode = Player.REPEAT_MODE_ONE
            }
        }
    }

    private fun resolveUrl(item: QueueItem): String {
        return PlaybackQueuePolicy.resolvePath(item.path, baseUrlProvider?.invoke().orEmpty())
    }

    private fun mediaItem(index: Int, item: QueueItem): MediaItem = MediaItem.Builder()
        .setUri(resolveUrl(item))
        .setCustomCacheKey(resolveUrl(item))
        .setMediaId(index.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtworkUri(artworkUri)
                .apply { if (item.subtitle.isNotBlank()) setArtist(item.subtitle) }
                .build()
        )
        .build()

    fun toggle() {
        val p = player ?: return
        userWantsPlayback = !userWantsPlayback
        if (userWantsPlayback) {
            ensurePlaybackService()
            p.play()
        } else p.pause()
        _state.value = _state.value.copy(playWhenReady = userWantsPlayback, playing = userWantsPlayback && p.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        player?.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
        persistSnapshot(force = true)
    }

    fun stop() {
        userWantsPlayback = false
        player?.stop()
        _state.value = PlaybackState()
        snapshotStore?.clear()
        appContext?.let { context ->
            context.stopService(Intent(context, com.moodify.app.PlaybackService::class.java))
        }
    }

    /** Called from a UI ticker to refresh position/duration. */
    fun tick() {
        val p = player ?: return
        val s = _state.value
        if (s.url == null) return
        _state.value = s.copy(
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.coerceAtLeast(0),
            playing = p.isPlaying,
        )
        persistSnapshot(force = false)
    }

    fun release() {
        persistSnapshot(force = true)
        routeMonitor?.stop()
        routeMonitor = null
        session?.release()
        session = null
        player?.release()
        player = null
    }

    /** Keep the Bearer header in sync with the current pair token. */
    private fun refreshAuthHeader() {
        val token = tokenProvider?.invoke()
        dataSourceFactory?.setDefaultRequestProperties(
            if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        )
    }

    private fun ensurePlaybackService() {
        val context = appContext ?: return
        ContextCompat.startForegroundService(
            context,
            Intent(context, com.moodify.app.PlaybackService::class.java),
        )
    }

    private fun restoreSnapshot() {
        val snapshot = snapshotStore?.read() ?: return
        val p = player ?: return
        val index = snapshot.queueIndex.coerceIn(snapshot.queue.indices)
        val current = snapshot.queue[index]
        userWantsPlayback = false
        _state.value = PlaybackState(
            title = current.title,
            url = resolveUrl(current),
            playWhenReady = false,
            positionMs = snapshot.positionMs,
            queue = snapshot.queue,
            queueIndex = index,
            outputRoute = _state.value.outputRoute,
            playbackMode = _state.value.playbackMode,
            history = _state.value.history,
        )
        p.setMediaItems(snapshot.queue.mapIndexed(::mediaItem), index, snapshot.positionMs)
        applyPlaybackMode(_state.value.playbackMode, index, snapshot.queue)
        // Setting a shuffle order may move Media3 to that order's first item.
        // Reassert the persisted item and position so every mode resumes exactly where the user left.
        p.seekTo(index, snapshot.positionMs)
        p.playWhenReady = false
        p.prepare()
    }

    private fun persistSnapshot(force: Boolean) {
        val s = _state.value
        if (s.queue.isEmpty() || s.queueIndex !in s.queue.indices) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSnapshotWriteElapsedMs < 5_000) return
        lastSnapshotWriteElapsedMs = now
        snapshotStore?.write(
            PlaybackSnapshot(
                queue = s.queue,
                queueIndex = player?.currentMediaItemIndex?.takeIf { it in s.queue.indices } ?: s.queueIndex,
                positionMs = player?.currentPosition?.coerceAtLeast(0) ?: s.positionMs,
            )
        )
    }

    private fun recoverFromFailure() {
        val p = player ?: return
        val s = _state.value
        val failedIndex = p.currentMediaItemIndex.takeIf { it in s.queue.indices } ?: s.queueIndex
        if (failedIndex in s.queue.indices) failedQueueIndices += failedIndex
        val next = PlaybackFailurePolicy.nextUnfailed(failedIndex, s.queue.size, failedQueueIndices)
        if (next == null) {
            userWantsPlayback = false
            p.playWhenReady = false
            _state.value = s.copy(
                playing = false,
                playWhenReady = false,
                buffering = false,
                error = "所有作品暂时无法播放",
            )
            return
        }
        val skippedTitle = s.queue.getOrNull(failedIndex)?.title.orEmpty()
        beginTransition()
        _state.value = s.copy(
            queueIndex = next,
            title = s.queue[next].title,
            url = resolveUrl(s.queue[next]),
            buffering = true,
            error = if (skippedTitle.isBlank()) "已跳过无法播放的作品" else "已跳过：$skippedTitle",
        )
        p.seekToDefaultPosition(next)
        p.prepare()
        p.playWhenReady = userWantsPlayback
    }

    private fun beginTransition() {
        transitionStartedElapsedMs = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(buffering = true, transitionLatencyMs = null)
    }

    private fun finishTransitionIfAlreadyReady() {
        if (player?.playbackState == Player.STATE_READY) finishTransition()
    }

    private fun finishTransition() {
        val started = transitionStartedElapsedMs ?: return
        val latency = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
        transitionStartedElapsedMs = null
        _state.value = _state.value.copy(buffering = false, transitionLatencyMs = latency)
        Log.i("RongjingPlayback", "item_to_ready_ms=$latency index=${player?.currentMediaItemIndex}")
    }

    private fun prefetchAfter(index: Int, queue: List<QueueItem>) {
        if (queue.isEmpty()) return
        val ordered = queue.drop(index + 1) + queue.take(index + 1)
        audioCache?.prefetch(ordered.map(::resolveUrl))
    }
}
