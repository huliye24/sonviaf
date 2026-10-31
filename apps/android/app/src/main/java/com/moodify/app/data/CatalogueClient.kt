package com.moodify.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public Music catalogue DTOs — shared meaning with the Web client contract. */
data class CatalogueTrack(
    val id: String,
    val title: String,
    val creatorId: String,
    val creatorHandle: String?,
    val status: String,
    val primaryLanguage: String?,
    val durationMs: Long?,
    val publishedAt: String?,
    val audioAssetKey: String?,
    val coverAssetKey: String?,
    val lyricsAssetKey: String?,
    val media: CatalogueMedia?,
    val coverAsset: CatalogueAsset?,
    val lyricsAsset: CatalogueAsset?,
    val deliveries: List<CatalogueDelivery>,
) {
    fun selectedDelivery(): CatalogueDelivery? = deliveries.firstOrNull {
        it.profile == "mobile-aac-lc-320-v1" && it.media.codec == "aac" &&
            (it.media.truePeakDbfs ?: Double.POSITIVE_INFINITY) <= -1.0
    }

    fun playbackMedia(): CatalogueMedia? = selectedDelivery()?.media ?: media

    fun audioUrl(base: String): String? {
        val delivery = selectedDelivery()
        val key = delivery?.assetKey ?: audioAssetKey ?: return null
        val selectedMedia = delivery?.media ?: media
        return base + key + selectedMedia?.assetVersion?.let { "?v=${it.removePrefix("sha256:")}" }.orEmpty()
    }

    fun coverUrl(base: String, fallback: String): String = coverAssetKey?.let { key ->
        base + key + coverAsset?.assetVersion?.let { "?v=${it.removePrefix("sha256:")}" }.orEmpty()
    } ?: fallback

    companion object {
        fun from(json: JSONObject): CatalogueTrack {
            val version = json.optJSONObject("version")
            return CatalogueTrack(
                id = json.optString("id"),
                title = json.optString("title"),
                creatorId = json.optString("creator_id"),
                creatorHandle = json.optString("creator_handle", "").ifEmpty { null },
                status = json.optString("status", "draft"),
                primaryLanguage = json.optString("primary_language", "").ifEmpty { null },
                durationMs = if (json.has("duration_ms") && !json.isNull("duration_ms")) json.optLong("duration_ms") else null,
                publishedAt = json.optString("published_at", "").ifEmpty { null },
                audioAssetKey = version?.optString("audio_asset_key", "")?.ifEmpty { null },
                coverAssetKey = version?.optString("cover_asset_key", "")?.ifEmpty { null },
                lyricsAssetKey = version?.optString("lyrics_asset_key", "")?.ifEmpty { null },
                media = version?.optJSONObject("media")?.let(CatalogueMedia::from),
                coverAsset = version?.optJSONObject("cover_asset")?.let(CatalogueAsset::from),
                lyricsAsset = version?.optJSONObject("lyrics_asset")?.let(CatalogueAsset::from),
                deliveries = buildList {
                    val items = version?.optJSONArray("deliveries") ?: return@buildList
                    for (index in 0 until items.length()) {
                        CatalogueDelivery.from(items.optJSONObject(index))?.let(::add)
                    }
                },
            )
        }
    }
}

data class CatalogueAsset(
    val assetVersion: String,
    val sha256: String,
    val bytes: Long,
    val contentType: String,
) {
    companion object {
        fun from(json: JSONObject): CatalogueAsset? {
            val value = CatalogueAsset(
                assetVersion = json.optString("asset_version"),
                sha256 = json.optString("sha256"),
                bytes = json.optLong("bytes", -1),
                contentType = json.optString("content_type"),
            )
            return value.takeIf {
                it.assetVersion.isNotBlank() && it.sha256.length == 64 && it.bytes >= 0 && it.contentType.isNotBlank()
            }
        }
    }
}

data class CatalogueDelivery(
    val profile: String,
    val assetKey: String,
    val purpose: String,
    val lossy: Boolean,
    val gainAdjustmentDb: Double,
    val truePeakLimitDbfs: Double,
    val media: CatalogueMedia,
) {
    companion object {
        fun from(json: JSONObject?): CatalogueDelivery? {
            json ?: return null
            val media = json.optJSONObject("media")?.let(CatalogueMedia::from) ?: return null
            val value = CatalogueDelivery(
                profile = json.optString("profile"),
                assetKey = json.optString("asset_key"),
                purpose = json.optString("purpose"),
                lossy = json.optBoolean("lossy", true),
                gainAdjustmentDb = json.optDouble("gain_adjustment_db", 0.0),
                truePeakLimitDbfs = json.optDouble("true_peak_limit_dbfs", 0.0),
                media = media,
            )
            return value.takeIf { it.profile.isNotBlank() && it.assetKey.isNotBlank() }
        }
    }
}

/** Probe-derived facts about the exact public media object; never inferred from its suffix. */
data class CatalogueMedia(
    val assetVersion: String,
    val sha256: String,
    val bytes: Long,
    val container: String,
    val codec: String,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val channels: Int,
    val durationMs: Long,
    val integratedLufs: Double?,
    val truePeakDbfs: Double?,
) {
    fun qualityLabel(): String {
        val format = when {
            codec.equals("flac", ignoreCase = true) -> "FLAC"
            codec.startsWith("pcm_", ignoreCase = true) -> "PCM"
            codec.equals("mp3", ignoreCase = true) -> "MP3"
            else -> codec.uppercase()
        }
        val rate = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000} kHz"
        else "${sampleRateHz / 1000.0} kHz"
        return listOfNotNull(format, bitDepth?.let { "$it-bit" }, rate).joinToString(" · ")
    }

    companion object {
        fun from(json: JSONObject): CatalogueMedia? {
            val assetVersion = json.optString("asset_version")
            val sha256 = json.optString("sha256")
            val container = json.optString("container")
            val codec = json.optString("codec")
            val bytes = json.optLong("bytes", -1)
            val sampleRateHz = json.optInt("sample_rate_hz", -1)
            val channels = json.optInt("channels", -1)
            val durationMs = json.optLong("duration_ms", -1)
            if (assetVersion.isBlank() || sha256.length != 64 || container.isBlank() || codec.isBlank() ||
                bytes < 0 || sampleRateHz <= 0 || channels <= 0 || durationMs < 0
            ) return null
            return CatalogueMedia(
                assetVersion = assetVersion,
                sha256 = sha256,
                bytes = bytes,
                container = container,
                codec = codec,
                sampleRateHz = sampleRateHz,
                bitDepth = json.optNullableInt("bit_depth"),
                channels = channels,
                durationMs = durationMs,
                integratedLufs = json.optNullableDouble("integrated_lufs"),
                truePeakDbfs = json.optNullableDouble("true_peak_dbfs"),
            )
        }
    }
}

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null

data class Catalogue(val tracks: List<CatalogueTrack>, val nextCursor: String? = null) {
    companion object {
        fun from(json: JSONObject): Catalogue = Catalogue(
            tracks = buildList {
                val array = json.optJSONArray("tracks") ?: return@buildList
                for (index in 0 until array.length()) add(CatalogueTrack.from(array.getJSONObject(index)))
            },
            nextCursor = json.optString("next_cursor", "").ifEmpty { null },
        )
    }
}

/**
 * Public Music BFF client — anonymous /api/v1/music only.
 * Never talks to PolarDB; never holds internal service keys.
 */
class CatalogueClient(private val baseUrl: String = "https://rongjingmusic.com/api/v1/music") {

    companion object {
        private const val CACHE = "moodify_music_catalogue"
        private const val CACHE_TRACKS = "playable_tracks"

        /**
         * First-party works used by the internal listening bench when the
         * catalogue service is unavailable. This is a UI fallback, not a
         * second catalogue authority; a healthy BFF response is merged in.
         */
        fun internalTestCatalogue(): Catalogue = Catalogue(
            listOf(
                testTrack("wo-ceng-yaowang-yinhe-xingguang", "我曾遥望银河星光", 164_000),
                testTrack("bao-yu-ye", "暴雨夜", 249_000),
                testTrack("iron-banner-at-dawn", "Iron Banner at Dawn", 124_000),
                testTrack("clash-above-the-crimson-plain", "Clash Above the Crimson Plain", 192_000),
                testTrack("midnight-rooftops-in-the-rain", "Midnight Rooftops in the Rain", 164_000),
                testTrack("rain-over-old-roof-tiles", "Rain Over Old Roof Tiles", 181_000),
                testTrack("tea-steam-in-slow-motion", "Tea Steam In Slow Motion", 169_000),
            )
        )

        private fun testTrack(slug: String, title: String, durationMs: Long) = CatalogueTrack(
            id = "internal-$slug",
            title = title,
            creatorId = "rongjingwenchuan",
            creatorHandle = "荣景文川",
            status = "published",
            primaryLanguage = if (title.firstOrNull()?.code?.let { it > 127 } == true) "zh-CN" else "en",
            durationMs = durationMs,
            publishedAt = null,
            audioAssetKey = "zh-cn/$slug.mp3",
            coverAssetKey = null,
            lyricsAssetKey = null,
            media = null,
            coverAsset = null,
            lyricsAsset = null,
            deliveries = emptyList(),
        )

        fun cached(context: Context): Catalogue? = runCatching {
            val raw = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
                .getString(CACHE_TRACKS, null) ?: return null
            val array = JSONArray(raw)
            Catalogue(buildList {
                for (index in 0 until array.length()) add(CatalogueTrack.from(array.getJSONObject(index)))
            }).takeIf { it.tracks.isNotEmpty() }
        }.getOrNull()

        fun cache(context: Context, catalogue: Catalogue) {
            val array = JSONArray()
            catalogue.tracks.forEach { track ->
                array.put(JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("creator_id", track.creatorId)
                    put("creator_handle", track.creatorHandle)
                    put("status", track.status)
                    put("primary_language", track.primaryLanguage)
                    put("duration_ms", track.durationMs)
                    put("published_at", track.publishedAt)
                    put("version", JSONObject().apply {
                        put("audio_asset_key", track.audioAssetKey)
                        put("cover_asset_key", track.coverAssetKey)
                        put("lyrics_asset_key", track.lyricsAssetKey)
                        track.media?.let { media ->
                            put("media", JSONObject().apply {
                                put("asset_version", media.assetVersion)
                                put("sha256", media.sha256)
                                put("bytes", media.bytes)
                                put("container", media.container)
                                put("codec", media.codec)
                                put("sample_rate_hz", media.sampleRateHz)
                                put("bit_depth", media.bitDepth)
                                put("channels", media.channels)
                                put("duration_ms", media.durationMs)
                                put("integrated_lufs", media.integratedLufs)
                                put("true_peak_dbfs", media.truePeakDbfs)
                            })
                        }
                        track.coverAsset?.let { put("cover_asset", it.toJson()) }
                        track.lyricsAsset?.let { put("lyrics_asset", it.toJson()) }
                        put("deliveries", JSONArray().apply {
                            track.deliveries.forEach { delivery -> put(delivery.toJson()) }
                        })
                    })
                })
            }
            context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
                .edit().putString(CACHE_TRACKS, array.toString()).apply()
        }
    }

    fun catalogue(): Catalogue {
        val tracks = mutableListOf<CatalogueTrack>()
        var cursor: String? = null
        do {
            val suffix = if (cursor == null) {
                "/catalogue?limit=100"
            } else {
                "/catalogue?limit=100&cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}"
            }
            val page = Catalogue.from(getJson(suffix))
            tracks += page.tracks
            cursor = page.nextCursor
        } while (cursor != null)
        return Catalogue(tracks)
    }

    /**
     * Catalogue rows intentionally omit the media version. Resolve each public
     * track through its canonical detail endpoint before presenting it as
     * playable; this avoids inventing fallback media in the client.
     */
    fun playableCatalogue(): Catalogue {
        // Studio currently publishes owner-controlled works to this manifest.
        // Read it even when the older BFF is healthy: a successful but stale
        // BFF response must not hide a newly published owner work.
        val ownerTracks = runCatching {
            Catalogue.from(getAbsoluteJson("https://rongjingmusic.com/internal-catalogue.json?v=${System.currentTimeMillis()}"))
                .tracks.filter { it.audioAssetKey != null }
        }.getOrDefault(emptyList())
        val bffTracks = runCatching {
            catalogue().tracks.mapNotNull { summary ->
                if (summary.audioAssetKey != null) summary
                else runCatching { track(summary.id) }.getOrNull()?.takeIf { it.audioAssetKey != null }
            }
        }.getOrDefault(emptyList())
        val merged = (ownerTracks + bffTracks).distinctBy { it.id }
        if (merged.isEmpty()) throw ConnectionError.NotFound("catalogue unavailable")
        return Catalogue(merged)
    }

    fun track(id: String): CatalogueTrack = CatalogueTrack.from(getJson("/tracks/$id"))

    private fun getJson(path: String): JSONObject = getAbsoluteJson("$baseUrl$path")

    private fun getAbsoluteJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "{}"
        conn.disconnect()
        val json = JSONObject(if (body.isEmpty()) "{}" else body)
        if (status !in 200..299) throw ConnectionError.NotFound("catalogue unavailable ($status)")
        return json
    }
}

private fun CatalogueAsset.toJson() = JSONObject().apply {
    put("asset_version", assetVersion)
    put("sha256", sha256)
    put("bytes", bytes)
    put("content_type", contentType)
}

private fun CatalogueDelivery.toJson() = JSONObject().apply {
    put("profile", profile)
    put("asset_key", assetKey)
    put("purpose", purpose)
    put("lossy", lossy)
    put("gain_adjustment_db", gainAdjustmentDb)
    put("true_peak_limit_dbfs", truePeakLimitDbfs)
    put("media", JSONObject().apply {
        put("asset_version", media.assetVersion)
        put("sha256", media.sha256)
        put("bytes", media.bytes)
        put("container", media.container)
        put("codec", media.codec)
        put("sample_rate_hz", media.sampleRateHz)
        put("bit_depth", media.bitDepth)
        put("channels", media.channels)
        put("duration_ms", media.durationMs)
        put("integrated_lufs", media.integratedLufs)
        put("true_peak_dbfs", media.truePeakDbfs)
    })
}
