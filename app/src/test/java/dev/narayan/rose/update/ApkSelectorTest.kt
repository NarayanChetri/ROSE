package dev.narayan.rose.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkSelectorTest {

    private val sampleAssets = listOf(
        ApkSelector.Asset("ROSE-v1.2.5-arm64-v8a-(Most-Phones).apk", "https://example.com/arm64.apk"),
        ApkSelector.Asset("ROSE-v1.2.5-armeabi-v7a-(Old-Phones).apk", "https://example.com/armv7.apk"),
        ApkSelector.Asset("ROSE-v1.2.5-universal-(All-Devices).apk", "https://example.com/universal.apk"),
        ApkSelector.Asset("ROSE-v1.2.5-x86_64-(Emulators).apk", "https://example.com/x86_64.apk"),
        ApkSelector.Asset("ROSE-v1.2.5-source.tar.gz", "https://example.com/source.tar.gz")
    )

    @Test
    fun testSelectsArm64ForModernPhone() {
        val selected = ApkSelector.selectBestApk(
            sampleAssets,
            supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        )
        assertEquals("https://example.com/arm64.apk", selected)
    }

    @Test
    fun testSelectsArmV7ForOlderPhone() {
        val selected = ApkSelector.selectBestApk(
            sampleAssets,
            supportedAbis = arrayOf("armeabi-v7a", "armeabi")
        )
        assertEquals("https://example.com/armv7.apk", selected)
    }

    @Test
    fun testSelectsX86_64ForEmulator() {
        val selected = ApkSelector.selectBestApk(
            sampleAssets,
            supportedAbis = arrayOf("x86_64", "arm64-v8a")
        )
        assertEquals("https://example.com/x86_64.apk", selected)
    }

    @Test
    fun testFallsBackToUniversalWhenNoAbiMatch() {
        val assets = listOf(
            ApkSelector.Asset("ROSE-v1.2.5-universal-(All-Devices).apk", "https://example.com/universal.apk"),
            ApkSelector.Asset("ROSE-v1.2.5-x86_64-(Emulators).apk", "https://example.com/x86_64.apk")
        )
        val selected = ApkSelector.selectBestApk(
            assets,
            supportedAbis = arrayOf("arm64-v8a")
        )
        assertEquals("https://example.com/universal.apk", selected)
    }

    @Test
    fun testStandardGradleSplitsNaming() {
        val assets = listOf(
            ApkSelector.Asset("app-arm64-v8a-release.apk", "https://example.com/arm64.apk"),
            ApkSelector.Asset("app-armeabi-v7a-release.apk", "https://example.com/armv7.apk"),
            ApkSelector.Asset("app-universal-release.apk", "https://example.com/universal.apk"),
            ApkSelector.Asset("app-x86_64-release.apk", "https://example.com/x86_64.apk")
        )
        val selected = ApkSelector.selectBestApk(
            assets,
            supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals("https://example.com/arm64.apk", selected)
    }

    @Test
    fun testSingleApkReturnsImmediately() {
        val assets = listOf(
            ApkSelector.Asset("ROSE-1.2.5.apk", "https://example.com/rose.apk")
        )
        val selected = ApkSelector.selectBestApk(
            assets,
            supportedAbis = arrayOf("arm64-v8a")
        )
        assertEquals("https://example.com/rose.apk", selected)
    }

    @Test
    fun testNoApkReturnsNull() {
        val assets = listOf(
            ApkSelector.Asset("ROSE-1.2.5.zip", "https://example.com/rose.zip")
        )
        val selected = ApkSelector.selectBestApk(
            assets,
            supportedAbis = arrayOf("arm64-v8a")
        )
        assertNull(selected)
    }
}
