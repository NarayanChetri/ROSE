package dev.narayan.rose.document

import android.net.Uri
import java.io.File

sealed class DocumentSource {
    abstract val title: String
    abstract val isReadOnly: Boolean

    data class FileSource(
        val file: File,
        override val isReadOnly: Boolean = !file.canWrite()
    ) : DocumentSource() {
        override val title: String get() = file.name
    }

    data class UriSource(
        val uri: Uri,
        val displayName: String,
        override val isReadOnly: Boolean
    ) : DocumentSource() {
        override val title: String get() = displayName
    }

    data class VirtualZipSource(
        val archiveFile: File,
        val entryPath: String,
        val displayName: String
    ) : DocumentSource() {
        override val isReadOnly: Boolean get() = true
        override val title: String get() = displayName
    }
}

enum class LineEnding(val separator: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR");

    companion object {
        fun detect(text: String): LineEnding {
            val crlfIndex = text.indexOf("\r\n")
            val lfIndex = text.indexOf("\n")
            val crIndex = text.indexOf("\r")

            return when {
                crlfIndex != -1 -> CRLF
                lfIndex != -1 -> LF
                crIndex != -1 -> CR
                else -> LF
            }
        }
    }
}

enum class DocumentMode {
    VIEW,
    EDIT
}

data class DocumentStats(
    val lineCount: Int,
    val wordCount: Int,
    val charCount: Int,
    val byteSize: Long,
    val encoding: String,
    val lineEnding: LineEnding
) {
    companion object {
        fun compute(text: String, byteSize: Long, encoding: String, lineEnding: LineEnding): DocumentStats {
            val charCount = text.length
            val lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
            val wordCount = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
            return DocumentStats(
                lineCount = lineCount,
                wordCount = wordCount,
                charCount = charCount,
                byteSize = byteSize,
                encoding = encoding,
                lineEnding = lineEnding
            )
        }
    }
}

