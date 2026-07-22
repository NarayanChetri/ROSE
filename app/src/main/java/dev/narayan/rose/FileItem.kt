package dev.narayan.rose

import java.io.File

data class FileItem(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val extension: String,
    val mimeType: String? = null,
    val itemCount: Int? = null,
    val virtualZipSource: File? = null,
    // Full path of this entry inside the archive (e.g. "folder/sub/file.txt"),
    // used for zip lookups since `name` is just the display basename.
    val zipEntryPath: String? = null,
    val bucketId: String? = null,
    val thumbnailPath: String? = null
) {
    // Secondary constructor for convenience that still does the IO hits
    constructor(file: File, itemCount: Int? = null, virtualZipSource: File? = null) : this(
        file = file,
        isDirectory = file.isDirectory,
        name = file.name,
        size = if (file.isFile) file.length() else 0,
        lastModified = file.lastModified(),
        extension = file.extension.lowercase(),
        mimeType = null,
        itemCount = itemCount,
        virtualZipSource = virtualZipSource
    )

    val fileType: FileType = when {
        isDirectory -> FileType.FOLDER
        extension == "apk" || mimeType == "application/vnd.android.package-archive" -> FileType.APK
        extension in listOf("zip", "rar", "7z", "tar", "gz", "tgz") ||
                mimeType in listOf("application/zip", "application/x-7z-compressed", "application/x-rar-compressed", "application/x-tar", "application/gzip") -> FileType.ZIP
        extension == "pdf" || mimeType == "application/pdf" -> FileType.PDF
        extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") ||
                mimeType?.startsWith("image/") == true -> FileType.IMAGE
        extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm") ||
                mimeType?.startsWith("video/") == true -> FileType.VIDEO
        extension in listOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "wma") ||
                mimeType?.startsWith("audio/") == true -> FileType.AUDIO
        extension in listOf("txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm", "xml", "json") ||
                mimeType?.startsWith("text/") == true ||
                mimeType in listOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") -> FileType.DOCUMENT
        else -> FileType.OTHER
    }
}

enum class FileType {
    FOLDER, APK, ZIP, PDF, IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER
}