package dev.narayan.rose.ftpserver

import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.User

class RoseFileSystemFactory : FileSystemFactory {
    override fun createFileSystemView(user: User): FileSystemView = RoseFileSystemView(user)
}

