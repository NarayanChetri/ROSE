package dev.narayan.rose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class RestrictedFileProviderTest {

    @Test
    fun testPathEncodingAndDecodingWithSpecialCharacters() {
        val testPaths = listOf(
            "/storage/emulated/0/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/Saved/Paks/game_video.mp4",
            "/storage/emulated/0/Android/data/org.telegram.messenger/cache/Telegram Video #1 (HD 1080p).mp4",
            "/storage/emulated/0/Android/data/com.whatsapp/Media/WhatsApp Video/VID_20260930_123456.mp4",
            "/storage/emulated/0/Android/obb/com.dts.freefireth/main.2019116666.com.dts.freefireth.obb",
            "/storage/emulated/0/Android/data/com.test/files/My Video [2026] + special & chars %20 @!#$.mkv",
            "/storage/emulated/0/Android/data/com.test/files/🎬 Japanese 映画 2026.mp4"
        )

        for (originalPath in testPaths) {
            val encoded = RestrictedFileProvider.encodePath(originalPath)
            val decoded = RestrictedFileProvider.decodePath(encoded)
            assertNotNull("Decoded path should not be null for: $originalPath", decoded)
            assertEquals("Decoded path must match original path exactly", originalPath, decoded)
        }
    }
}
