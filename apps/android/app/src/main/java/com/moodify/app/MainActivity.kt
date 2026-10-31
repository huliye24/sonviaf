package com.moodify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.core.os.LocaleListCompat
import com.moodify.app.data.LocaleKit
import com.moodify.app.data.PlaybackManager
import com.moodify.app.ui.MoodifyApp
import com.moodify.app.ui.theme.MoodifyTheme
import java.util.Locale

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureInitialLocale()
        setContent {
            MoodifyTheme {
                LaunchedEffect(Unit) {
                    // Draw the player first. ExoPlayer, MediaSession and snapshot restoration are
                    // deliberately deferred until after the first frame so cold start never waits
                    // on the audio stack.
                    withFrameNanos { }
                    PlaybackManager.init(applicationContext)
                    handleExternalAudio(intent)
                    requestNotificationPermissionIfNeeded()
                }
                MoodifyApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalAudio(intent)
    }

    private fun handleExternalAudio(intent: Intent?) {
        val sourceIntent = intent ?: return
        val uris = (listOfNotNull(sourceIntent.data) + sourceIntent.sharedUris())
            .filter { it.scheme == "content" || it.scheme == "file" }
            .distinct()
        if (uris.isEmpty()) return

        if (sourceIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        PlaybackManager.playExternal(uris.map { uri -> uri to displayName(uri) })
    }

    private fun Intent.sharedUris(): List<Uri> {
        val direct = if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::listOf).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            runCatching { getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let(::listOf).orEmpty()
        }
        val multiple = if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            runCatching { getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull().orEmpty()
        }
        val clipped = clipData?.let { data ->
            (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri }
        }.orEmpty()
        return (direct + multiple + clipped).distinct()
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (cursor?.moveToFirst() == true) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name.substringBeforeLast('.')
                }
            } catch (_: RuntimeException) {
                // Fall through to the URI-derived title.
            } finally {
                cursor?.close()
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?.takeIf(String::isNotBlank) ?: "本地音频"
    }

    /** Android 13+ requires runtime consent for the media notification. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    /**
     * First launch follows the system language, resolved through LocaleKit's alias table
     * (e.g. zh-HK -> zh-TW) so that resource fallback cannot silently land on English.
     * A user's explicit choice in Settings overrides this because it persists.
     */
    private fun ensureInitialLocale() {
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
        val normalized = LocaleKit.normalize(Locale.getDefault().toLanguageTag())
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
    }
}
