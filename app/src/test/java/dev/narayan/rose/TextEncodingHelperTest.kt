package dev.narayan.rose

import dev.narayan.rose.document.LineEnding
import dev.narayan.rose.document.TextEncodingHelper
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class TextEncodingHelperTest {

    @Test
    fun testReadUtf8WithoutBom() {
        val sample = "Hello World\nThis is ROSE file manager."
        val stream = ByteArrayInputStream(sample.toByteArray(StandardCharsets.UTF_8))
        val result = TextEncodingHelper.readText(stream)

        assertEquals("Hello World\nThis is ROSE file manager.", result.content)
        assertEquals("UTF-8", result.charsetName)
        assertEquals(LineEnding.LF, result.lineEnding)
        assertFalse(result.hasBom)
        assertFalse(result.isTruncated)
    }

    @Test
    fun testReadUtf8WithBom() {
        val sample = "Markdown Document with BOM"
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val textBytes = sample.toByteArray(StandardCharsets.UTF_8)
        val combined = bomBytes + textBytes

        val stream = ByteArrayInputStream(combined)
        val result = TextEncodingHelper.readText(stream)

        assertEquals("Markdown Document with BOM", result.content)
        assertEquals("UTF-8", result.charsetName)
        assertTrue(result.hasBom)
    }

    @Test
    fun testReadUtf16BeWithBom() {
        val sample = "UTF-16 Big Endian text"
        val bomBytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val textBytes = sample.toByteArray(StandardCharsets.UTF_16BE)
        val combined = bomBytes + textBytes

        val stream = ByteArrayInputStream(combined)
        val result = TextEncodingHelper.readText(stream)

        assertEquals(sample, result.content)
        assertEquals("UTF-16BE", result.charsetName)
        assertTrue(result.hasBom)
    }

    @Test
    fun testReadUtf16LeWithBom() {
        val sample = "UTF-16 Little Endian text"
        val bomBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val textBytes = sample.toByteArray(StandardCharsets.UTF_16LE)
        val combined = bomBytes + textBytes

        val stream = ByteArrayInputStream(combined)
        val result = TextEncodingHelper.readText(stream)

        assertEquals(sample, result.content)
        assertEquals("UTF-16LE", result.charsetName)
        assertTrue(result.hasBom)
    }

    @Test
    fun testFallbackToIso88591ForNonUtf8() {
        // 0xE9 in ISO-8859-1 is 'é'. In UTF-8, 0xE9 is an incomplete 3-byte sequence prefix without continuation bytes
        val invalidUtf8Bytes = byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        val stream = ByteArrayInputStream(invalidUtf8Bytes)
        val result = TextEncodingHelper.readText(stream)

        assertEquals("ISO-8859-1", result.charsetName)
        assertEquals("Café", result.content)
        assertFalse(result.hasBom)
    }

    @Test
    fun testLineEndingDetectionAndNormalization() {
        val crlfText = "Line 1\r\nLine 2\r\nLine 3"
        val streamCrlf = ByteArrayInputStream(crlfText.toByteArray(StandardCharsets.UTF_8))
        val resultCrlf = TextEncodingHelper.readText(streamCrlf)
        assertEquals(LineEnding.CRLF, resultCrlf.lineEnding)

        val lfText = "Line 1\nLine 2\nLine 3"
        val streamLf = ByteArrayInputStream(lfText.toByteArray(StandardCharsets.UTF_8))
        val resultLf = TextEncodingHelper.readText(streamLf)
        assertEquals(LineEnding.LF, resultLf.lineEnding)

        // Normalization tests
        val normalizedToLf = TextEncodingHelper.normalizeLineEndings(crlfText, LineEnding.LF)
        assertEquals("Line 1\nLine 2\nLine 3", normalizedToLf)

        val normalizedToCrlf = TextEncodingHelper.normalizeLineEndings(lfText, LineEnding.CRLF)
        assertEquals("Line 1\r\nLine 2\r\nLine 3", normalizedToCrlf)
    }

    @Test
    fun testEmptyFileHandling() {
        val stream = ByteArrayInputStream(ByteArray(0))
        val result = TextEncodingHelper.readText(stream)

        assertEquals("", result.content)
        assertEquals(0L, result.byteSize)
        assertFalse(result.isTruncated)
    }

    @Test
    fun testTruncationGuardForLargeStreams() {
        val largeText = "A".repeat(1000)
        val stream = ByteArrayInputStream(largeText.toByteArray(StandardCharsets.UTF_8))
        val result = TextEncodingHelper.readText(stream, maxBytes = 250)

        assertEquals(250, result.content.length)
        assertTrue(result.isTruncated)
    }
}

