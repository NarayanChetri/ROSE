package dev.narayan.rose

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MergeCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Handles access to `Android/data` and `Android/obb` without root/Shizuku,
 * and generalized access to other SAF Tree URIs (like Google Drive or SD Cards).
 */
object SafManager {

    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val DOC_ID_ROOT = "primary:"
    private const val DOC_ID_ANDROID = "primary:Android"
    private const val DOC_ID_ANDROID_DATA = "primary:Android/data"
    private const val DOC_ID_ANDROID_OBB = "primary:Android/obb"

    /** Tree Uri for the whole "This device" storage root - the single permission we request. */
    private val ROOT_TREE_URI: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, DOC_ID_ROOT)

    /** Document Uri (not tree) for the root - used only as the picker's starting location. */
    private val ROOT_DOCUMENT_URI: Uri = DocumentsContract.buildDocumentUri(AUTHORITY, DOC_ID_ROOT)

    fun isSafUri(path: String): Boolean = path.startsWith("content://") || path.startsWith("/content:/")

    private fun normalizeSafPath(path: String): String {
        if (path.startsWith("/content:/")) {
            return path.substring(1).replaceFirst("content:/", "content://")
        }
        return path
    }

    // ---------------------------------------------------------------------
    // Path classification
    // ---------------------------------------------------------------------

    private fun primaryStorage(): String = android.os.Environment.getExternalStorageDirectory().absolutePath

    /** True if [path] is inside Android/data or Android/obb on primary storage. */
    fun isRestrictedPath(path: String): Boolean {
        if (isSafUri(path)) return true
        val primary = primaryStorage()
        return path == "$primary/Android/data" || path.startsWith("$primary/Android/data/") ||
                path == "$primary/Android/obb" || path.startsWith("$primary/Android/obb/")
    }

    /** Splits a restricted [path] into (documentId of Android/data or Android/obb, relative path beyond it). */
    private fun splitAndroidSubRoot(path: String): Pair<String, String>? {
        val primary = primaryStorage()
        val dataRoot = "$primary/Android/data"
        val obbRoot = "$primary/Android/obb"
        val (docId, root) = when {
            path == dataRoot || path.startsWith("$dataRoot/") -> DOC_ID_ANDROID_DATA to dataRoot
            path == obbRoot || path.startsWith("$obbRoot/") -> DOC_ID_ANDROID_OBB to obbRoot
            else -> return null
        }
        return docId to path.removePrefix(root).removePrefix("/")
    }

    private fun documentIdForPath(path: String): String? {
        val (rootDocId, relative) = splitAndroidSubRoot(path) ?: return null
        return if (relative.isEmpty()) rootDocId else "$rootDocId/$relative"
    }

    // ---------------------------------------------------------------------
    // Permission - ONE root grant covers both Android/data and Android/obb
    // ---------------------------------------------------------------------

    private fun persistedRootTreeUri(context: Context): Uri? {
        val rootUriString = ROOT_TREE_URI.toString()
        return context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .find { it.toString() == rootUriString }
    }

    fun hasRootPermission(context: Context): Boolean = persistedRootTreeUri(context) != null

    /** Kept for call-site compatibility - any restricted path just checks the single root grant. */
    fun hasPermission(context: Context, path: String): Boolean {
        if (isSafUri(path)) return true // Assuming we have permission if we have the URI
        if (!isRestrictedPath(path)) return true
        return hasRootPermission(context)
    }

    /** One-time root grant request - points the system picker straight at the storage root. */
    fun requestRootPermission(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            putExtra("android.provider.extra.INITIAL_URI", ROOT_DOCUMENT_URI)
            putExtra("android.provider.extra.SHOW_ADVANCED", true)
        }
    }

    /** Kept for call-site compatibility - path is only used to confirm it's actually restricted. */
    fun requestPermission(context: Context, path: String): Intent? {
        if (isSafUri(path)) return null
        if (!isRestrictedPath(path)) return null
        return requestRootPermission()
    }

    // ---------------------------------------------------------------------
    // DocumentFile resolution (core of every operation below)
    // ---------------------------------------------------------------------

    /**
     * Resolves an arbitrary Android/data or Android/obb [path] to its DocumentFile.
     */
    fun getDocumentFile(context: Context, path: String): DocumentFile? {
        if (isSafUri(path)) {
            return try {
                DocumentFile.fromSingleUri(context, Uri.parse(normalizeSafPath(path)))
            } catch (e: Exception) {
                null
            }
        }
        val treeUri = persistedRootTreeUri(context) ?: return null
        val (rootDocId, relativePath) = splitAndroidSubRoot(path) ?: return null
        var doc = DocumentFile.fromSingleUri(
            context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        ) ?: return null
        if (relativePath.isEmpty()) return doc

        for (part in relativePath.split("/").filter { it.isNotEmpty() }) {
            doc = doc.findFile(part) ?: return null
        }
        return doc
    }

    private fun getOrCreateParentDocumentFile(context: Context, path: String): DocumentFile? {
        val parentPath = path.substringBeforeLast("/", "")
        if (parentPath.isEmpty()) return null
        return getDocumentFile(context, parentPath)
    }

    fun exists(context: Context, path: String): Boolean {
        if (isSafUri(path)) {
            return try {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(normalizeSafPath(path)))
                doc?.exists() == true
            } catch (e: Exception) {
                false
            }
        }
        val doc = getDocumentFile(context, path)
        return doc != null && doc.exists()
    }

    fun isDirectory(context: Context, path: String): Boolean =
        getDocumentFile(context, path)?.isDirectory == true

    /** Content Uri for a path, suitable for ACTION_VIEW / ACTION_SEND with FLAG_GRANT_READ_URI_PERMISSION. */
    fun getContentUri(context: Context, path: String): Uri? = getDocumentFile(context, path)?.uri

    // ---------------------------------------------------------------------
    // Listing
    // ---------------------------------------------------------------------

    fun listFiles(context: Context, path: String): List<FileItem> {
        if (isSafUri(path)) {
            return listFilesByUri(context, Uri.parse(normalizeSafPath(path)))
        }

        val treeUri = persistedRootTreeUri(context) ?: return emptyList()
        val documentId = documentIdForPath(path) ?: return emptyList()

        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val rawCursor = context.contentResolver.query(childrenUri, null, null, null, null)
                ?: return emptyList()

            val cursor = if (documentId == DOC_ID_ANDROID) {
                mergeHiddenAndroidChildren(context, treeUri, rawCursor)
            } else {
                rawCursor
            }

            val results = mutableListOf<FileItem>()
            cursor.use { c ->
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val lastModIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)

                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val isDir = c.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR
                    val mime = c.getString(mimeIdx)
                    val id = c.getString(idIdx)

                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

                    results.add(
                        FileItem(
                            file = File(childUri.toString()),
                            isDirectory = isDir,
                            name = name,
                            size = c.getLong(sizeIdx),
                            lastModified = c.getLong(lastModIdx),
                            extension = name.substringAfterLast('.', "").lowercase(),
                            mimeType = mime
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            // Fallback to DocumentFile traversal if the direct cursor query fails
            try {
                val document = getDocumentFile(context, path) ?: return emptyList()
                document.listFiles().map { doc ->
                    val name = doc.name ?: ""
                    FileItem(
                        file = File(doc.uri.toString()),
                        isDirectory = doc.isDirectory,
                        name = name,
                        size = doc.length(),
                        lastModified = doc.lastModified(),
                        extension = name.substringAfterLast('.', "").lowercase(),
                        mimeType = doc.type
                    )
                }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    fun listFilesByUri(context: Context, uri: Uri): List<FileItem> {
        val results = mutableListOf<FileItem>()
        try {
            val authority = uri.authority ?: return emptyList()

            // Extract the tree ID and document ID. Even if it's a subfolder, 
            // we need the tree ID to list its children.
            val treeId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e: Exception) {
                null
            }

            val documentId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (e: Exception) {
                treeId
            }

            if (treeId == null || documentId == null) return emptyList()

            val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

            context.contentResolver.query(childrenUri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val lastModIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)

                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val mime = c.getString(mimeIdx)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val id = c.getString(idIdx)

                    // Always build using the treeUri to maintain access permissions
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

                    results.add(
                        FileItem(
                            file = File(childUri.toString()),
                            isDirectory = isDir,
                            name = name,
                            size = c.getLong(sizeIdx),
                            lastModified = c.getLong(lastModIdx),
                            extension = name.substringAfterLast('.', "").lowercase(),
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (e: Exception) {}
        return results
    }

    /**
     * Android's ExternalStorageProvider hides Android/data and Android/obb from the
     * normal children query of Android/ - re-query them directly by document ID and
     * merge them back in. This is the same hack Material Files ships
     * (ExternalStorageProviderHacks.kt).
     */
    private fun mergeHiddenAndroidChildren(context: Context, treeUri: Uri, cursor: Cursor): Cursor {
        var hasData = false
        var hasObb = false
        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        while (cursor.moveToNext()) {
            when (cursor.getString(idIdx)) {
                DOC_ID_ANDROID_DATA -> hasData = true
                DOC_ID_ANDROID_OBB -> hasObb = true
            }
        }
        cursor.moveToPosition(-1)

        if (hasData && hasObb) return cursor

        val cursors = mutableListOf(cursor)
        if (!hasData) {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DOC_ID_ANDROID_DATA), null, null, null, null
            )?.let { cursors += it }
        }
        if (!hasObb) {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DOC_ID_ANDROID_OBB), null, null, null, null
            )?.let { cursors += it }
        }
        return MergeCursor(cursors.toTypedArray())
    }

    fun listChildPaths(context: Context, path: String): List<String> {
        val doc = getDocumentFile(context, path) ?: return emptyList()
        if (!doc.isDirectory) return emptyList()
        return doc.listFiles().mapNotNull { it.uri.toString() }
    }

    // ---------------------------------------------------------------------
    // Create / Delete / Rename
    // ---------------------------------------------------------------------

    fun createDirectory(context: Context, path: String): Boolean {
        if (exists(context, path)) return isDirectory(context, path)
        val doc = getDocumentFile(context, path.substringBeforeLast("/")) ?: return false
        return doc.createDirectory(path.substringAfterLast("/")) != null
    }

    fun createFileDocument(context: Context, path: String, mimeType: String? = null): DocumentFile? {
        val fileName = path.substringAfterLast("/")
        val extension = fileName.substringAfterLast(".", "")

        val resolvedMimeType = mimeType ?: if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else {
            null
        } ?: "application/octet-stream"

        val parent = getOrCreateParentDocumentFile(context, path) ?: return null

        // IMPORTANT: always keep the full file name, extension included.
        // Neither Android's local ExternalStorageProvider nor Google Drive's
        // DocumentsProvider append the extension back for you - stripping it
        // here is what caused "Save as offline" / copy results to land on
        // disk with no extension (unknown file type when reopened). The one
        // thing we DO want to avoid is a double extension if the provider
        // *does* echo it back, so we check the resulting name after creation
        // and repair it below rather than guessing beforehand.
        parent.findFile(fileName)?.delete()
        val created = parent.createFile(resolvedMimeType, fileName) ?: return null

        val actualName = created.name
        if (extension.isNotEmpty() && actualName != null && actualName != fileName) {
            // Some providers append their own extension guess (e.g. turning
            // "photo.jpg" into "photo.jpg.jpg"). Rename back to what we asked
            // for so the file we track on disk actually matches.
            if (actualName.endsWith(".$extension.$extension") || actualName == "$fileName.$extension") {
                DocumentsContract.renameDocument(context.contentResolver, created.uri, fileName)
            } else if (!actualName.contains(".") && fileName.contains(".")) {
                DocumentsContract.renameDocument(context.contentResolver, created.uri, fileName)
            }
        }

        return DocumentFile.fromSingleUri(context, created.uri) ?: created
    }

    /**
     * Best-effort file size for a SAF/content path. DocumentFile.length() is
     * lazy for some cloud providers (Google Drive, etc.) and can report 0
     * even for a file that clearly has content, which is what causes
     * download progress to look "stuck at 0%". Cross-check against the
     * OpenableColumns/Document cursor, which providers usually populate even
     * when the DocumentFile wrapper hasn't resolved a length yet.
     */
    fun getReliableSize(context: Context, path: String): Long {
        val doc = getDocumentFile(context, path) ?: return 0L
        val fromDoc = doc.length()
        if (fromDoc > 0) return fromDoc

        return try {
            context.contentResolver.query(doc.uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Some cloud providers - Google Drive chief among them - report a
     * COLUMN_DISPLAY_NAME with no extension for perfectly ordinary binary
     * files (a video or image uploaded through the web, say) even though the
     * MIME type is known and correct. Writing that name straight to local
     * storage lands on disk with no extension - "unknown file" the moment it
     * leaves this app (can't be opened as .mp4/.png by anything else). If the
     * name is missing an extension MimeTypeMap recognizes, but the MIME type
     * maps to a real one, append that instead of trusting the bare name.
     */
    fun ensureFileExtension(displayName: String, mimeType: String?): String {
        val existingExt = displayName.substringAfterLast('.', "")
        if (existingExt.isNotEmpty() && MimeTypeMap.getSingleton().hasExtension(existingExt.lowercase())) {
            return displayName
        }
        if (mimeType.isNullOrBlank()) return displayName
        val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: return displayName
        if (existingExt.equals(extFromMime, ignoreCase = true)) return displayName
        return "$displayName.$extFromMime"
    }

    fun delete(context: Context, path: String): Boolean {
        val doc = getDocumentFile(context, path) ?: return true // already gone
        return doc.delete()
    }

    fun rename(context: Context, path: String, newName: String): Boolean {
        val doc = getDocumentFile(context, path) ?: return false
        return try {
            DocumentsContract.renameDocument(context.contentResolver, doc.uri, newName) != null
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // Streams
    // ---------------------------------------------------------------------

    fun openInputStream(context: Context, path: String): InputStream? {
        val doc = getDocumentFile(context, path) ?: return null
        return context.contentResolver.openInputStream(doc.uri)
    }

    fun openOutputStreamForNewFile(context: Context, path: String, mimeType: String? = null): OutputStream? {
        val doc = createFileDocument(context, path, mimeType) ?: return null
        return context.contentResolver.openOutputStream(doc.uri)
    }
}