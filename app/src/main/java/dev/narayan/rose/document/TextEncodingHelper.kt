package dev.narayan.rose.document

import android.content.Context
import android.net.Uri
import dev.narayan.rose.rescanForMediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TextEncodingHelper {

    // 5 MB limit for full in-memory interactive editing
    const val MAX_EDIT_BYTES = 5L * 1024L * 1024L

    data class LoadedText(
        val content: String,
        val charsetName: String,
        val lineEnding: LineEnding,
        val byteSize: Long,
        val isTruncated: Boolean = false,
        val hasBom: Boolean = false
    )

    /**
     * Reads text safely from an InputStream, detecting BOM, Charset, and line endings.
     * If the stream length exceeds [maxBytes], it safely reads up to [maxBytes] and marks
     * [LoadedText.isTruncated] as true.
     */
    fun readText(inputStream: InputStream, maxBytes: Long = MAX_EDIT_BYTES): LoadedText {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var totalRead = 0L
        var isTruncated = false

        while (true) {
            val toRead = if (maxBytes > 0) {
                val remaining = maxBytes - totalRead
                if (remaining <= 0) {
                    isTruncated = true
                    break
                }
                buffer.size.toLong().coerceAtMost(remaining).toInt()
            } else {
                buffer.size
            }

            val read = inputStream.read(buffer, 0, toRead)
            if (read == -1) break
            baos.write(buffer, 0, read)
            totalRead += read
        }

        // Also check if there were more bytes available in the stream
        if (!isTruncated && inputStream.read() != -1) {
            isTruncated = true
            totalRead++
        }

        val bytes = baos.toByteArray()
        val byteSize = totalRead

        if (bytes.isEmpty()) {
            return LoadedText(
                content = "",
                charsetName = "UTF-8",
                lineEnding = LineEnding.LF,
                byteSize = 0L,
                isTruncated = false,
                hasBom = false
            )
        }

        // BOM Detection
        var offset = 0
        var detectedCharset: Charset? = null
        var hasBom = false

        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            detectedCharset = StandardCharsets.UTF_8
            offset = 3
            hasBom = true
        } else if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            detectedCharset = StandardCharsets.UTF_16BE
            offset = 2
            hasBom = true
        } else if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            detectedCharset = StandardCharsets.UTF_16LE
            offset = 2
            hasBom = true
        }

        // If no BOM, test strict UTF-8 decoding
        if (detectedCharset == null) {
            val byteBuffer = ByteBuffer.wrap(bytes)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

            detectedCharset = try {
                decoder.decode(byteBuffer)
                StandardCharsets.UTF_8
            } catch (e: Exception) {
                // Not strictly UTF-8, fall back safely to ISO-8859-1 (Latin-1) which preserves every byte 0..255
                StandardCharsets.ISO_8859_1
            }
        }

        val textLength = bytes.size - offset
        val decoded = String(bytes, offset, textLength, detectedCharset)
        val lineEnding = LineEnding.detect(decoded)

        return LoadedText(
            content = decoded,
            charsetName = detectedCharset.name(),
            lineEnding = lineEnding,
            byteSize = byteSize,
            isTruncated = isTruncated,
            hasBom = hasBom
        )
    }

    /**
     * Normalizes line endings to the given style.
     */
    fun normalizeLineEndings(text: String, lineEnding: LineEnding): String {
        val standardLf = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (lineEnding == LineEnding.LF) {
            standardLf
        } else {
            standardLf.replace("\n", lineEnding.separator)
        }
    }

    /**
     * Atomically saves text to a File on disk with the given encoding and line endings.
     */
    fun saveFile(
        context: Context,
        targetFile: File,
        content: String,
        charsetName: String = "UTF-8",
        lineEnding: LineEnding = LineEnding.LF,
        includeBom: Boolean = false
    ): Result<Unit> {
        return try {
            val parentDir = targetFile.parentFile ?: context.cacheDir
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }

            val tempFile = File(parentDir, ".${targetFile.name}.${System.currentTimeMillis()}.tmp")
            val normalized = normalizeLineEndings(content, lineEnding)
            val charset = try { Charset.forName(charsetName) } catch (e: Exception) { StandardCharsets.UTF_8 }
            val textBytes = normalized.toByteArray(charset)

            FileOutputStream(tempFile).use { fos ->
                if (includeBom && charset == StandardCharsets.UTF_8) {
                    fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                }
                fos.write(textBytes)
                fos.flush()
                fos.channel.force(true)
            }

            // Atomic rename or fallback replacement
            if (!tempFile.renameTo(targetFile)) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.inputStream().use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                }
            }

            // Notify Android MediaStore of updated file size and timestamp
            rescanForMediaStore(context, targetFile)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Safely saves text to a content/file Uri via ContentResolver.
     */
    fun saveUri(
        context: Context,
        targetUri: Uri,
        content: String,
        charsetName: String = "UTF-8",
        lineEnding: LineEnding = LineEnding.LF,
        includeBom: Boolean = false
    ): Result<Unit> {
        return try {
            val normalized = normalizeLineEndings(content, lineEnding)
            val charset = try { Charset.forName(charsetName) } catch (e: Exception) { StandardCharsets.UTF_8 }
            val textBytes = normalized.toByteArray(charset)

            context.contentResolver.openOutputStream(targetUri, "wt")?.use { os ->
                if (includeBom && charset == StandardCharsets.UTF_8) {
                    os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                }
                os.write(textBytes)
                os.flush()
            } ?: throw IOException("Could not open stream for $targetUri")

            if (targetUri.scheme == "file" && targetUri.path != null) {
                rescanForMediaStore(context, File(targetUri.path!!))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

