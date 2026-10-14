package dev.narayan.rose.ftpserver

import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.usermanager.impl.WriteRequest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels

class RoseFtpFile(
    val file: File,
    private val relativePath: String,
    private val user: User,
    private val rootDirectory: File
) : Comparable<RoseFtpFile>, FtpFile {

    override fun getAbsolutePath(): String {
        val clean = relativePath.trim().trim('/')
        return if (clean.isEmpty()) "/" else "/$clean"
    }

    override fun getName(): String {
        val clean = relativePath.trim().trim('/')
        if (clean.isEmpty()) return "/"
        val lastSlash = clean.lastIndexOf('/')
        return if (lastSlash >= 0) clean.substring(lastSlash + 1) else clean
    }

    override fun isHidden(): Boolean = file.isHidden

    override fun isDirectory(): Boolean = file.isDirectory

    override fun isFile(): Boolean = file.isFile

    override fun doesExist(): Boolean = file.exists()

    override fun isReadable(): Boolean = file.canRead()

    override fun isWritable(): Boolean {
        if (user.authorize(WriteRequest(absolutePath)) == null) {
            return false
        }
        return !file.exists() || file.canWrite()
    }

    override fun isRemovable(): Boolean {
        if (absolutePath == "/") {
            return false
        }
        if (user.authorize(WriteRequest(absolutePath)) == null) {
            return false
        }
        val parent = file.parentFile
        return parent != null && parent.canWrite()
    }

    override fun getOwnerName(): String = "user"

    override fun getGroupName(): String = "group"

    override fun getLinkCount(): Int = if (isDirectory) 3 else 1

    override fun getLastModified(): Long = file.lastModified()

    override fun setLastModified(time: Long): Boolean {
        if (!isWritable) return false
        return file.setLastModified(time)
    }

    override fun getSize(): Long = if (isDirectory) 0L else file.length()

    override fun getPhysicalFile(): Any = file

    override fun mkdir(): Boolean {
        if (!isWritable) return false
        return file.mkdir()
    }

    override fun delete(): Boolean {
        if (!isRemovable) return false
        return file.delete()
    }

    override fun move(destination: FtpFile): Boolean {
        if (!(isRemovable && destination.isWritable)) return false
        val destFile = (destination as? RoseFtpFile)?.file ?: return false
        return file.renameTo(destFile)
    }

    override fun listFiles(): List<RoseFtpFile>? {
        if (!file.isDirectory) return null
        val children = file.listFiles() ?: return emptyList()
        val canonicalRoot = try { rootDirectory.canonicalPath } catch (e: Exception) { rootDirectory.absolutePath }

        return children.map { child ->
            val childCanonical = try { child.canonicalPath } catch (e: Exception) { child.absolutePath }
            val childRel = if (childCanonical.startsWith(canonicalRoot)) {
                childCanonical.removePrefix(canonicalRoot).replace(File.separatorChar, '/').trimStart('/')
            } else {
                child.name
            }
            RoseFtpFile(child, childRel, user, rootDirectory)
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    override fun createOutputStream(offset: Long): OutputStream {
        if (!isWritable) {
            throw IOException("File is not writable: $absolutePath")
        }
        return if (offset == 0L) {
            FileOutputStream(file)
        } else {
            val raf = RandomAccessFile(file, "rw")
            if (offset < raf.length()) {
                raf.setLength(offset)
            }
            raf.seek(offset)
            Channels.newOutputStream(raf.channel)
        }
    }

    @Throws(IOException::class)
    override fun createInputStream(offset: Long): InputStream {
        return if (offset == 0L) {
            FileInputStream(file)
        } else {
            val raf = RandomAccessFile(file, "r")
            raf.seek(offset)
            Channels.newInputStream(raf.channel)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoseFtpFile) return false
        return file == other.file
    }

    override fun hashCode(): Int = file.hashCode()

    override fun compareTo(other: RoseFtpFile): Int = file.compareTo(other.file)
}

