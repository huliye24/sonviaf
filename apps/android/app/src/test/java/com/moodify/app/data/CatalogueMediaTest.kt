package com.moodify.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogueMediaTest {
    @Test
    fun `parses probe facts without trusting asset suffix`() {
        val track = CatalogueTrack.from(JSONObject("""
            {
              "id":"work-1", "title":"作品", "creator_id":"creator", "status":"published",
              "duration_ms":121960,
              "version": {
                "audio_asset_key":"album/master.wav",
                "cover_asset_key":"album/cover.webp",
                "cover_asset":{"asset_version":"sha256:fedcba9876543210","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","bytes":99,"content_type":"image/webp"},
                "media": {
                  "asset_version":"sha256:0123456789abcdef",
                  "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "bytes":11834704, "container":"flac", "codec":"flac",
                  "sample_rate_hz":44100, "bit_depth":16, "channels":2,
                  "duration_ms":121960, "integrated_lufs":-13.8, "true_peak_dbfs":-0.7
                },
                "deliveries":[{
                  "profile":"mobile-aac-lc-320-v1", "asset_key":"delivery/work-1.m4a",
                  "purpose":"mobile-bluetooth-browser", "lossy":true, "gain_adjustment_db":-0.4,
                  "true_peak_limit_dbfs":-1.0,
                  "media":{"asset_version":"sha256:1111222233334444","sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","bytes":1000,"container":"mov","codec":"aac","sample_rate_hz":44100,"bit_depth":null,"channels":2,"duration_ms":121960,"integrated_lufs":-14.2,"true_peak_dbfs":-1.2}
                }]
              }
            }
        """))

        assertEquals("album/master.wav", track.audioAssetKey)
        assertEquals("flac", track.media?.codec)
        assertEquals(44_100, track.media?.sampleRateHz)
        assertEquals(-13.8, track.media?.integratedLufs!!, 0.001)
        assertEquals("FLAC · 16-bit · 44.1 kHz", track.media?.qualityLabel())
        assertEquals("https://media/delivery/work-1.m4a?v=1111222233334444", track.audioUrl("https://media/"))
        assertEquals("aac", track.playbackMedia()?.codec)
        assertEquals("https://media/album/cover.webp?v=fedcba9876543210", track.coverUrl("https://media/", "fallback"))
    }

    @Test
    fun `rejects incomplete media facts while keeping playable legacy track`() {
        val track = CatalogueTrack.from(JSONObject("""
            {"id":"legacy", "title":"旧作品", "creator_id":"creator", "status":"published",
             "version":{"audio_asset_key":"legacy.mp3", "media":{"codec":"mp3"}}}
        """))

        assertEquals("legacy.mp3", track.audioAssetKey)
        assertNull(track.media)
    }
}
