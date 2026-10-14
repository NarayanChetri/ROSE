package dev.narayan.rose.ftpserver

import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.File

class RoseFileSystemView(private val user: User) : FileSystemView {
    private val rootDirectory: File = File(user.homeDirectory ?: "/").canonicalFile
    private val homeFtpFile: RoseFtpFile = RoseFtpFile(rootDirectory, "", user, rootDirectory)
    private var workingFtpFile: RoseFtpFile = homeFtpFile

    override fun getHomeDirectory(): FtpFile = homeFtpFile

    override fun getWorkingDirectory(): FtpFile = workingFtpFile

    override fun changeWorkingDirectory(dir: String): Boolean {
        val target = getFile(dir)
        if (!target.doesExist() || !target.isDirectory) {
            return false
        }
        workingFtpFile = target as RoseFtpFile
        return true
    }

    override fun getFile(fileString: String): FtpFile {
        val isAbsolute = fileString.startsWith("/")
        val parent = if (isAbsolute) rootDirectory else workingFtpFile.file
        val relative = if (isAbsolute) fileString.drop(1) else fileString

        val target = if (relative.isEmpty()) {
            parent
        } else {
            File(parent, relative)
        }

        val canonicalTarget = try { target.canonicalFile } catch (e: Exception) { target.absoluteFile }
        val canonicalRoot = rootDirectory.canonicalFile

        // Path traversal protection: ensure target file is within rootDirectory
        if (!canonicalTarget.path.startsWith(canonicalRoot.path)) {
            return homeFtpFile
        }

        val relPath = if (canonicalTarget == canonicalRoot) {
            ""
        } else {
            canonicalTarget.path.removePrefix(canonicalRoot.path).replace(File.separatorChar, '/').trimStart('/')
        }

        return RoseFtpFile(canonicalTarget, relPath, user, rootDirectory)
    }

    override fun isRandomAccessible(): Boolean = true

    override fun dispose() {}
}

