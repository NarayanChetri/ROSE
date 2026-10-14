package dev.narayan.rose.ftpserver

import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class FtpServer(
    private val username: String,
    private val password: String?,
    private val port: Int,
    private val homeDirectory: File,
    private val writable: Boolean
) {
    private var server: FtpServer? = null

    @Throws(FtpException::class, RuntimeException::class)
    fun start() {
        val serverFactory = FtpServerFactory().apply {
            val listener = ListenerFactory().apply {
                port = this@FtpServer.port
            }.createListener()
            addListener("default", listener)

            val user = BaseUser().apply {
                name = username
                password = this@FtpServer.password
                authorities = if (writable) listOf(WritePermission()) else emptyList()
                homeDirectory = this@FtpServer.homeDirectory.absolutePath
            }
            userManager.save(user)

            fileSystem = RoseFileSystemFactory()
            connectionConfig = ConnectionConfigFactory().apply {
                isAnonymousLoginEnabled = true
            }.createConnectionConfig()
        }
        val created = serverFactory.createServer()
        server = created
        created.start()
    }

    fun stop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            server = null
        }
    }
}

