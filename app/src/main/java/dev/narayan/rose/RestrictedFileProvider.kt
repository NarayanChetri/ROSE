package dev.narayan.rose

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File

/**
 * ContentProvider that securely exposes restricted files (such as inside Android/data
 * or Android/obb) to external apps (like video players) via direct seekable ParcelFileDescriptors
 * powered by Shizuku / SAF without copying files to cache or blocking the UI thread.
 */
class RestrictedFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val path = getPathFromUri(uri) ?: return null
        val file = File(path)
        val name = file.name

        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()

        val size = if (file.exists() && file.canRead()) {
            file.length()
        } else {
            ShizukuManager.getFileSize(path)
        }

        for (col in cols) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> row.add(name)
                OpenableColumns.SIZE -> row.add(size)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val path = getPathFromUri(uri) ?: return null
        val ext = path.substringAfterLast('.', "").lowercase()
        val mime = try {
            MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
        } catch (_: Throwable) {
            null
        }
        if (mime != null) return mime
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            else -> "application/octet-stream"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = getPathFromUri(uri) ?: return null
        val ctx = context ?: return null
        val modeBits = try {
            ParcelFileDescriptor.parseMode(mode)
        } catch (e: Exception) {
            ParcelFileDescriptor.MODE_READ_ONLY
        }

        // Fast path 1: Directly readable file (e.g. Android 10, or accessible directly)
        val directFile = File(path)
        if (directFile.exists() && directFile.canRead()) {
            try {
                return ParcelFileDescriptor.open(directFile, modeBits)
            } catch (_: Throwable) {}
        }

        // Fast path 2: Shizuku privileged FileDescriptor (direct kernel access to Android/data)
        if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
            val pfd = ShizukuManager.openFileDescriptor(ctx, path, modeBits)
            if (pfd != null) {
                return pfd
            }
        }

        // Fast path 3: SAF ContentResolver if permission is granted
        if (SafManager.hasPermission(ctx, path)) {
            val safUri = SafManager.getContentUri(ctx, path)
            if (safUri != null) {
                try {
                    return ctx.contentResolver.openFileDescriptor(safUri, mode)
                } catch (_: Throwable) {}
            }
        }

        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val AUTHORITY_SUFFIX = ".restricted_provider"

        fun getAuthority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

        fun encodePath(path: String): String {
            return try {
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                android.util.Base64.encodeToString(
                    path.toByteArray(Charsets.UTF_8),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
            }
        }

        fun decodePath(encoded: String): String {
            return try {
                String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
            } catch (_: Throwable) {
                String(
                    android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),
                    Charsets.UTF_8
                )
            }
        }

        fun getUriForFile(context: Context, file: File): Uri {
            val path = file.absolutePath
            val encodedPath = encodePath(path)
            val fileName = file.name
            return Uri.parse("content://${getAuthority(context)}/file/$encodedPath/$fileName")
        }

        fun getPathFromUri(uri: Uri): String? {
            val segments = uri.pathSegments
            if (segments.size < 2 || segments[0] != "file") return null
            return try {
                decodePath(segments[1])
            } catch (e: Exception) {
                null
            }
        }
    }
}
