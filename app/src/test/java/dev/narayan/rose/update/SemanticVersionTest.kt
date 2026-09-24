package dev.narayan.rose.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun testBasicComparison() {
        assertTrue(SemanticVersion.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0", "2.0.0"))
        assertTrue(SemanticVersion.isNewerVersion("1.2.4", "1.2.5"))
        assertTrue(SemanticVersion.isNewerVersion("1.9.9", "1.10.0"))

        assertFalse(SemanticVersion.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.1.0", "1.0.0"))
        assertFalse(SemanticVersion.isNewerVersion("2.0.0", "1.0.0"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.4", "1.2.4"))
    }

    @Test
    fun testVPrefixHandling() {
        assertTrue(SemanticVersion.isNewerVersion("1.2.4", "v1.2.5"))
        assertTrue(SemanticVersion.isNewerVersion("v1.2.4", "1.2.5"))
        assertTrue(SemanticVersion.isNewerVersion("v1.2.4", "v1.3.0"))
        assertTrue(SemanticVersion.isNewerVersion("V1.0", "v1.1"))

        assertFalse(SemanticVersion.isNewerVersion("v1.2.4", "1.2.4"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.4", "v1.2.4"))
        assertFalse(SemanticVersion.isNewerVersion("v1.2.5", "v1.2.4"))
    }

    @Test
    fun testDifferentPartLengths() {
        assertTrue(SemanticVersion.isNewerVersion("1.2", "1.2.1"))
        assertTrue(SemanticVersion.isNewerVersion("1.2", "1.3"))
        assertTrue(SemanticVersion.isNewerVersion("1.2.4", "1.3"))

        assertFalse(SemanticVersion.isNewerVersion("1.2.0", "1.2"))
        assertFalse(SemanticVersion.isNewerVersion("1.2.1", "1.2"))
    }

    @Test
    fun testPreReleasePrecedence() {
        // Normal release > pre-release with same major.minor.patch
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-alpha", "1.0.0"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-rc.1", "1.0.0"))
        assertTrue(SemanticVersion.isNewerVersion("v1.3.0-beta", "v1.3.0"))

        assertFalse(SemanticVersion.isNewerVersion("1.0.0", "1.0.0-alpha"))
        assertFalse(SemanticVersion.isNewerVersion("1.0.0", "1.0.0-rc.1"))

        // Comparing pre-releases
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-alpha", "1.0.0-alpha.1"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-alpha.1", "1.0.0-alpha.beta"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-alpha.beta", "1.0.0-beta"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-beta", "1.0.0-beta.2"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-beta.2", "1.0.0-beta.11"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0-beta.11", "1.0.0-rc.1"))
    }

    @Test
    fun testBuildMetadataIgnored() {
        // Build metadata is ignored when comparing versions
        val v1 = SemanticVersion.parseOrNull("1.0.0+20130313144700")!!
        val v2 = SemanticVersion.parseOrNull("1.0.0+exp.sha.5114f85")!!
        assertEquals(0, v1.compareTo(v2))

        assertFalse(SemanticVersion.isNewerVersion("1.0.0+build1", "1.0.0+build2"))
        assertTrue(SemanticVersion.isNewerVersion("1.0.0+build1", "1.0.1+build2"))
    }

    @Test
    fun testSkippedVersionLogic() {
        // Current app version is 1.2.6
        // Release 1.3.0 is skipped
        val skipped = "v1.3.0"

        // Latest release is still 1.3.0 -> NOT newer than skipped
        assertFalse(SemanticVersion.isNewerVersion(skipped, "1.3.0"))
        assertFalse(SemanticVersion.isNewerVersion(skipped, "v1.3.0"))

        // Latest release is 1.3.1 -> strictly newer than skipped
        assertTrue(SemanticVersion.isNewerVersion(skipped, "1.3.1"))
        assertTrue(SemanticVersion.isNewerVersion(skipped, "v1.3.1"))
    }

    @Test
    fun testEdgeCasesAndWhitespace() {
        assertTrue(SemanticVersion.isNewerVersion(" 1.2.0 ", " 1.2.1 "))
        assertFalse(SemanticVersion.isNewerVersion("1.2.0", ""))
        assertFalse(SemanticVersion.isNewerVersion("1.2.0", null))
        assertTrue(SemanticVersion.isNewerVersion(null, "1.0.0"))
        assertTrue(SemanticVersion.isNewerVersion("", "1.0.0"))
    }
}

