package dev.narayan.rose

import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class RoseArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isEncrypted: Boolean = false
)

object ArchiveManager {

    private const val BUFFER_SIZE = 128 * 1024 // 128KB

    fun readEntries(file: File): List<RoseArchiveEntry> {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                readEntries(raf.channel)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to stream if channel fails
            try {
                FileInputStream(file).use { fis -> readEntries(fis) }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    fun readEntries(channel: FileChannel): List<RoseArchiveEntry> {
        val entries = mutableListOf<RoseArchiveEntry>()
        val archive = Archive.readNew()
        if (archive == 0L) return emptyList()
        try {
            setupReadArchive(archive, channel)
            Archive.readOpen1(archive)
            
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                
                val name = getEntryName(entry)
                val isDir = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR
                val size = ArchiveEntry.size(entry)
                val mtime = ArchiveEntry.mtime(entry) * 1000
                val isEncrypted = ArchiveEntry.isEncrypted(entry)
                
                entries.add(RoseArchiveEntry(name, isDir, size, mtime, isEncrypted))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            Archive.readFree(archive)
        }
        return entries
    }

    fun readEntries(inputStream: InputStream): List<RoseArchiveEntry> {
        val entries = mutableListOf<RoseArchiveEntry>()
        val archive = Archive.readNew()
        if (archive == 0L) return emptyList()
        try {
            setupReadArchive(archive, inputStream)
            Archive.readOpen1(archive)
            
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                
                val name = getEntryName(entry)
                val isDir = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR
                val size = ArchiveEntry.size(entry)
                val mtime = ArchiveEntry.mtime(entry) * 1000
                val isEncrypted = ArchiveEntry.isEncrypted(entry)
                
                entries.add(RoseArchiveEntry(name, isDir, size, mtime, isEncrypted))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            Archive.readFree(archive)
        }
        return entries
    }

    fun compress(sources: List<File>, targetFile: File, passphrase: String? = null, onProgress: (name: String, processed: Long) -> Unit) {
        val archive = Archive.writeNew()
        if (archive == 0L) throw Exception("Failed to initialize archive engine")
        var success = false
        try {
            Archive.writeSetFormatZip(archive)

            // Speed up compression by using a lower compression level.
            // Level 6 (default) is often too slow for mobile CPUs, while
            // Level 1-4 provides a much better speed/ratio balance.
            try {
                Archive.writeSetOptions(archive, "zip:compression-level=4".toByteArray())
            } catch (e: Throwable) {}

            if (!passphrase.isNullOrEmpty()) {
                try {
                    // zip:encryption=aes256 is the modern standard but requires a 
                    // libarchive built with crypto support (like mbedTLS or OpenSSL).
                    // We try AES256 first, then fallback to traditional zipcrypt.
                    try {
                        Archive.writeSetOptions(archive, "zip:encryption=aes256".toByteArray())
                    } catch (e: Throwable) {
                        try {
                            Archive.writeSetOptions(archive, "zip:encryption=zipcrypt".toByteArray())
                        } catch (e2: Throwable) {}
                    }
                    Archive.writeSetPassphrase(archive, passphrase.toByteArray())
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            
            Archive.writeOpenFileName(archive, targetFile.absolutePath.toByteArray())

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            sources.forEach { source ->
                addFileToArchive(archive, source, source.name, buffer, onProgress)
            }
            
            Archive.writeClose(archive)
            success = true
        } finally {
            Archive.writeFree(archive)
            if (!success && targetFile.exists()) {
                try { targetFile.delete() } catch (e: Exception) {}
            }
        }
    }

    private fun addFileToArchive(archive: Long, file: File, entryName: String, buffer: ByteBuffer, onProgress: (String, Long) -> Unit) {
        val entry = ArchiveEntry.new1()
        if (entry == 0L) return
        try {
            val name = if (file.isDirectory && !entryName.endsWith("/")) "$entryName/" else entryName
            ArchiveEntry.setPathnameUtf8(entry, name)
            ArchiveEntry.setSize(entry, if (file.isDirectory) 0 else file.length())
            ArchiveEntry.setFiletype(entry, if (file.isDirectory) ArchiveEntry.AE_IFDIR else ArchiveEntry.AE_IFREG)
            ArchiveEntry.setMtime(entry, file.lastModified() / 1000, 0)
            ArchiveEntry.setPerm(entry, if (file.isDirectory) 493 else 420)

            Archive.writeHeader(archive, entry)

            if (file.isFile) {
                FileInputStream(file).use { fis ->
                    val channel = fis.channel
                    while (true) {
                        buffer.clear()
                        val read = channel.read(buffer)
                        if (read <= 0) break
                        buffer.flip()
                        Archive.writeData(archive, buffer)
                        onProgress(file.name, read.toLong())
                    }
                }
            } else if (file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    addFileToArchive(archive, child, "$name${child.name}", buffer, onProgress)
                }
            }
        } finally {
            ArchiveEntry.free(entry)
        }
    }

    private fun getEntryName(entry: Long): String {
        return try {
            ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { String(it, Charsets.UTF_8) } ?: ""
        } catch (e: Exception) {
            ArchiveEntry.pathname(entry)?.let { String(it, Charsets.ISO_8859_1) } ?: ""
        }
    }

    fun extractEntry(file: File, entryPath: String, outputStream: OutputStream, passphrase: String? = null) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                extractEntry(raf.channel, entryPath, outputStream, passphrase)
            }
        } catch (e: Exception) {
            FileInputStream(file).use { fis -> extractEntry(fis, entryPath, outputStream, passphrase) }
        }
    }

    fun extractEntry(channel: FileChannel, entryPath: String, outputStream: OutputStream, passphrase: String? = null) {
        val archive = Archive.readNew()
        try {
            setupReadArchive(archive, channel, passphrase)
            Archive.readOpen1(archive)
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                if (getEntryName(entry) == entryPath) {
                    copyData(archive, outputStream)
                    return
                }
            }
        } finally {
            Archive.readFree(archive)
        }
    }

    fun extractEntry(inputStream: InputStream, entryPath: String, outputStream: OutputStream, passphrase: String? = null) {
        val archive = Archive.readNew()
        try {
            setupReadArchive(archive, inputStream, passphrase)
            Archive.readOpen1(archive)
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                if (getEntryName(entry) == entryPath) {
                    copyData(archive, outputStream)
                    return
                }
            }
        } finally {
            Archive.readFree(archive)
        }
    }

    fun extractTo(archiveFile: File, entryName: String, dest: File, passphrase: String? = null) {
        RandomAccessFile(archiveFile, "r").use { raf ->
            val channel = raf.channel
            val archive = Archive.readNew()
            try {
                setupReadArchive(archive, channel, passphrase)
                Archive.readOpen1(archive)
                
                val prefix = entryName.trimEnd('/') + "/"
                val destCanonicalPath = dest.canonicalPath
                
                while (true) {
                    val entry = Archive.readNextHeader(archive)
                    if (entry == 0L) break
                    
                    val name = getEntryName(entry)
                    val isDir = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR
                    
                    if (name == entryName || name == "$entryName/") {
                        if (!isDir) {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { output -> copyData(archive, output) }
                            return
                        }
                    } else if (name.startsWith(prefix)) {
                        val relative = name.removePrefix(prefix)
                        val childDest = File(dest, relative)
                        if (childDest.canonicalPath.startsWith(destCanonicalPath + File.separator) || childDest.canonicalPath == destCanonicalPath) {
                            if (isDir) {
                                childDest.mkdirs()
                            } else {
                                childDest.parentFile?.mkdirs()
                                childDest.outputStream().use { output -> copyData(archive, output) }
                            }
                        }
                    }
                }
            } finally {
                Archive.readFree(archive)
            }
        }
    }

    fun extractAll(archiveFile: File, passphrase: String? = null, onEntry: (name: String, isDirectory: Boolean, copyTask: (OutputStream) -> Unit) -> Unit) {
        RandomAccessFile(archiveFile, "r").use { raf ->
            val channel = raf.channel
            val archive = Archive.readNew()
            try {
                setupReadArchive(archive, channel, passphrase)
                Archive.readOpen1(archive)
                while (true) {
                    val entry = Archive.readNextHeader(archive)
                    if (entry == 0L) break
                    val name = getEntryName(entry)
                    val stat = ArchiveEntry.stat(entry)
                    val isDir = (stat.stMode and 0xf000) == 0x4000
                    onEntry(name, isDir) { output -> copyData(archive, output) }
                }
            } finally {
                Archive.readFree(archive)
            }
        }
    }

    private fun copyData(archive: Long, output: OutputStream) {
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        while (true) {
            buffer.clear()
            try {
                Archive.readData(archive, buffer)
            } catch (e: Exception) {
                if (e.message?.contains("Passphrase required") == true) {
                    throw Exception("Password required for this entry")
                }
                throw e
            }
            buffer.flip()
            if (buffer.remaining() == 0) break
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        }
    }

    fun getEntryBytes(file: File, entryPath: String, maxSize: Long = 10 * 1024 * 1024, passphrase: String? = null): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val archive = Archive.readNew()
                try {
                    setupReadArchive(archive, channel, passphrase)
                    Archive.readOpen1(archive)
                    while (true) {
                        val entry = Archive.readNextHeader(archive)
                        if (entry == 0L) break
                        if (getEntryName(entry) == entryPath) {
                            val stat = ArchiveEntry.stat(entry)
                            val size = stat.stSize
                            if (size > maxSize || size < 0) return null
                            val bytes = ByteArray(size.toInt())
                            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                            var offset = 0
                            while (offset < bytes.size) {
                                buffer.clear()
                                Archive.readData(archive, buffer)
                                buffer.flip()
                                val remaining = buffer.remaining()
                                if (remaining == 0) break
                                val toCopy = minOf(remaining, bytes.size - offset)
                                buffer.get(bytes, offset, toCopy)
                                offset += toCopy
                            }
                            return bytes
                        }
                    }
                    null
                } finally {
                    Archive.readFree(archive)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setupReadArchive(archive: Long, channel: FileChannel, passphrase: String? = null) {
        try { Archive.setCharset(archive, "UTF-8".toByteArray()) } catch (e: Throwable) {}
        Archive.readSupportFilterAll(archive)
        Archive.readSupportFormatAll(archive)
        if (passphrase != null) {
            try {
                Archive.readAddPassphrase(archive, passphrase.toByteArray())
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        Archive.readSetCallbackData(archive, null)
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        Archive.readSetReadCallback<Any?>(archive) { _, _ ->
            buffer.clear()
            try {
                val bytesRead = channel.read(buffer)
                if (bytesRead > 0) {
                    buffer.flip()
                    buffer
                } else null
            } catch (e: Exception) { null }
        }
        Archive.readSetSkipCallback<Any?>(archive) { _, _, request ->
            try {
                val oldPos = channel.position()
                channel.position(oldPos + request)
                request
            } catch (e: Exception) { 0L }
        }
        Archive.readSetSeekCallback<Any?>(archive) { _, _, offset, whence ->
            try {
                val newPos = when (whence) {
                    OsConstants.SEEK_SET -> offset
                    OsConstants.SEEK_CUR -> channel.position() + offset
                    OsConstants.SEEK_END -> channel.size() + offset
                    else -> -1L
                }
                if (newPos >= 0) {
                    channel.position(newPos)
                    newPos
                } else -1L
            } catch (e: Exception) { -1L }
        }
    }

    private fun setupReadArchive(archive: Long, inputStream: InputStream, passphrase: String? = null) {
        try { Archive.setCharset(archive, "UTF-8".toByteArray()) } catch (e: Throwable) {}
        Archive.readSupportFilterAll(archive)
        Archive.readSupportFormatAll(archive)
        if (passphrase != null) {
            try {
                Archive.readAddPassphrase(archive, passphrase.toByteArray())
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        Archive.readSetCallbackData(archive, null)
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        Archive.readSetReadCallback<Any?>(archive) { _, _ ->
            buffer.clear()
            try {
                val bytesRead = inputStream.read(buffer.array())
                if (bytesRead > 0) {
                    buffer.limit(bytesRead)
                    buffer
                } else null
            } catch (e: Exception) { null }
        }
        Archive.readSetSkipCallback<Any?>(archive) { _, _, request ->
            try { inputStream.skip(request) } catch (e: Exception) { 0L }
        }
    }

}
