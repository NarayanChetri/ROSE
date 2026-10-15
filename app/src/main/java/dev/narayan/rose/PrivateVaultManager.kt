package dev.narayan.rose

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class VaultItem(
    val id: String,
    val originalName: String,
    val originalPath: String,
    val dateAdded: Long,
    val originalLastModified: Long,
    val size: Long,
    val mimeType: String?,
    val fileType: FileType,
    val vaultPath: String? = null
)

object PrivateVaultManager {
    private const val VAULT_FOLDER_NAME = ".rose_vault"
    private const val NOMEDIA_FILE = ".nomedia"
    private const val METADATA_FILE = "vault_metadata.json"
    private const val VAULT_CONFIG_FILE = ".vault_config"
    private const val PREFS_NAME = "rose_vault_prefs"
    private const val KEY_PIN_HASH = "vault_pin_hash"
    private const val KEY_PIN_SALT = "vault_pin_salt"

    private val lock = Any()

    var isUnlocked: Boolean = false

    fun lock() {
        isUnlocked = false
    }

    // -------------------------------------------------------------------------
    // Storage & Directory Management (MX Player Style - Shared Storage)
    // -------------------------------------------------------------------------

    fun getVaultDirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()

        // 1. Primary shared storage root
        val primary = Environment.getExternalStorageDirectory()
        dirs.add(File(primary, VAULT_FOLDER_NAME))

        // 2. SD Cards and secondary mounted volumes
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.forEach { volume ->
                if (!volume.isPrimary && volume.state == Environment.MEDIA_MOUNTED) {
                    val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else {
                        try {
                            val getPath = volume.javaClass.getMethod("getPath")
                            File(getPath.invoke(volume) as String)
                        } catch (e: Exception) { null }
                    }
                    if (root != null) {
                        dirs.add(File(root, VAULT_FOLDER_NAME))
                    }
                }
            }
        } catch (e: Exception) {}

        // Ensure directories and .nomedia exist
        dirs.forEach { dir ->
            ensureVaultDir(dir)
        }
        return dirs
    }

    private fun ensureVaultDir(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val nomedia = File(dir, NOMEDIA_FILE)
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }
    }

    private fun getVaultDirForFile(context: Context, file: File): File {
        val absolutePath = file.absolutePath
        val dirs = getVaultDirs(context)
        return dirs.find { dir ->
            val root = dir.parentFile?.absolutePath ?: "???"
            absolutePath.startsWith(root)
        } ?: dirs.first()
    }

    private fun getMetadataFile(vaultDir: File): File {
        return File(vaultDir, METADATA_FILE)
    }

    // -------------------------------------------------------------------------
    // Metadata Persistence (Thread-safe & Atomic)
    // -------------------------------------------------------------------------

    private fun loadMetadata(vaultDir: File): MutableMap<String, VaultItem> {
        val metaFile = getMetadataFile(vaultDir)
        if (!metaFile.exists()) return mutableMapOf()

        return try {
            val text = metaFile.readText()
            if (text.isBlank()) return mutableMapOf()
            val json = JSONObject(text)
            val map = mutableMapOf<String, VaultItem>()
            json.keys().forEach { id ->
                val obj = json.getJSONObject(id)
                val typeStr = obj.optString("type", FileType.OTHER.name)
                val fileType = runCatching { FileType.valueOf(typeStr) }.getOrDefault(FileType.OTHER)
                map[id] = VaultItem(
                    id = id,
                    originalName = obj.getString("name"),
                    originalPath = obj.getString("path"),
                    dateAdded = obj.optLong("added", System.currentTimeMillis()),
                    originalLastModified = obj.optLong("modified", 0L),
                    size = obj.optLong("size", 0L),
                    mimeType = obj.optString("mime").takeIf { it.isNotBlank() },
                    fileType = fileType,
                    vaultPath = vaultDir.absolutePath
                )
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveMetadata(vaultDir: File, metadata: Map<String, VaultItem>): Boolean {
        return try {
            val json = JSONObject()
            metadata.forEach { (id, item) ->
                val obj = JSONObject().apply {
                    put("name", item.originalName)
                    put("path", item.originalPath)
                    put("added", item.dateAdded)
                    put("modified", item.originalLastModified)
                    put("size", item.size)
                    put("mime", item.mimeType ?: "")
                    put("type", item.fileType.name)
                }
                json.put(id, obj)
            }

            // Atomic file write using temporary file + rename
            val metaFile = getMetadataFile(vaultDir)
            val parent = metaFile.parentFile ?: vaultDir
            if (!parent.exists()) parent.mkdirs()

            val tempFile = File(parent, "${metaFile.name}.tmp-${UUID.randomUUID()}")
            tempFile.writeText(json.toString(2))
            if (tempFile.renameTo(metaFile)) {
                true
            } else {
                metaFile.writeText(json.toString(2))
                tempFile.delete()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // File Operations with Strict Interruption Safety (No File Loss)
    // -------------------------------------------------------------------------

    fun moveToVault(
        context: Context,
        file: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        if (!file.exists() || !file.isFile) return false

        val originalSize = file.length()
        val originalName = file.name
        val originalPath = file.absolutePath
        val originalLastModified = file.lastModified()
        val extension = file.extension.lowercase()

        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        val fileType = FileItem(file).fileType

        val vaultDir = getVaultDirForFile(context, file)
        ensureVaultDir(vaultDir)

        // Generate obfuscated identifier
        val uuid = UUID.randomUUID().toString()
        val vaultFileName = "$uuid.vault"
        val destination = File(vaultDir, vaultFileName)

        return try {
            var moveSuccess = false

            // Priority 1: Fast atomic rename on same volume
            if (file.renameTo(destination)) {
                moveSuccess = true
            } else {
                // Priority 2: Staged copy with size verification before deleting source
                val tempDest = File(vaultDir, "$vaultFileName.tmp-${UUID.randomUUID()}")
                try {
                    var bytesCopied = 0L
                    FileInputStream(file).use { input ->
                        FileOutputStream(tempDest).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } >= 0) {
                                output.write(buffer, 0, bytesRead)
                                bytesCopied += bytesRead
                                onProgress?.invoke(bytesCopied, originalSize)
                            }
                            output.fd.sync()
                        }
                    }

                    // Verify destination file size matches exactly before deleting source
                    if (tempDest.exists() && tempDest.length() == originalSize) {
                        if (tempDest.renameTo(destination)) {
                            file.delete()
                            moveSuccess = true
                        } else if (tempDest.exists()) {
                            tempDest.delete()
                        }
                    } else {
                        tempDest.delete()
                    }
                } catch (e: Exception) {
                    tempDest.delete()
                    return false
                }
            }

            if (moveSuccess) {
                val item = VaultItem(
                    id = vaultFileName,
                    originalName = originalName,
                    originalPath = originalPath,
                    dateAdded = System.currentTimeMillis(),
                    originalLastModified = originalLastModified,
                    size = originalSize,
                    mimeType = mimeType,
                    fileType = fileType,
                    vaultPath = vaultDir.absolutePath
                )

                synchronized(lock) {
                    val metadata = loadMetadata(vaultDir)
                    metadata[vaultFileName] = item
                    saveMetadata(vaultDir, metadata)
                }

                // Rescan original path so media scanner/gallery drops the file immediately
                rescanForMediaStore(context, file)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun restoreFromVault(
        context: Context,
        item: VaultItem,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        val vaultFile = getVaultFile(context, item)
        if (!vaultFile.exists()) {
            // Clean dead metadata
            item.vaultPath?.let {
                synchronized(lock) {
                    val dir = File(it)
                    val metadata = loadMetadata(dir)
                    metadata.remove(item.id)
                    saveMetadata(dir, metadata)
                }
            }
            return false
        }

        val targetOriginal = File(item.originalPath)
        val targetFile = getNonConflictingTarget(targetOriginal)
        targetFile.parentFile?.mkdirs()

        return try {
            var restoreSuccess = false

            // Try atomic rename first
            if (vaultFile.renameTo(targetFile)) {
                restoreSuccess = true
            } else {
                // Staged copy with size check
                val tempTarget = File(targetFile.parentFile, "${targetFile.name}.tmp-${UUID.randomUUID()}")
                try {
                    val totalBytes = vaultFile.length()
                    var bytesCopied = 0L
                    FileInputStream(vaultFile).use { input ->
                        FileOutputStream(tempTarget).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } >= 0) {
                                output.write(buffer, 0, bytesRead)
                                bytesCopied += bytesRead
                                onProgress?.invoke(bytesCopied, totalBytes)
                            }
                            output.fd.sync()
                        }
                    }

                    if (tempTarget.exists() && tempTarget.length() == totalBytes) {
                        if (tempTarget.renameTo(targetFile)) {
                            vaultFile.delete()
                            restoreSuccess = true
                        } else {
                            tempTarget.delete()
                        }
                    } else {
                        tempTarget.delete()
                    }
                } catch (e: Exception) {
                    tempTarget.delete()
                    return false
                }
            }

            if (restoreSuccess) {
                // Restore original timestamp if available
                if (item.originalLastModified > 0) {
                    targetFile.setLastModified(item.originalLastModified)
                }

                val vaultDir = vaultFile.parentFile ?: getVaultDirs(context).first()
                synchronized(lock) {
                    val metadata = loadMetadata(vaultDir)
                    metadata.remove(item.id)
                    saveMetadata(vaultDir, metadata)
                }

                // Rescan restored file so gallery and media players index it immediately
                rescanForMediaStore(context, targetFile)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun deletePermanently(context: Context, item: VaultItem): Boolean {
        val vaultFile = getVaultFile(context, item)
        val deleted = vaultFile.delete()

        if (deleted || !vaultFile.exists()) {
            val vaultDir = vaultFile.parentFile ?: getVaultDirs(context).first()
            synchronized(lock) {
                val metadata = loadMetadata(vaultDir)
                metadata.remove(item.id)
                saveMetadata(vaultDir, metadata)
            }
            return true
        }
        return false
    }

    fun listItems(context: Context): List<VaultItem> {
        val items = mutableListOf<VaultItem>()
        getVaultDirs(context).forEach { vaultDir ->
            synchronized(lock) {
                val metadata = loadMetadata(vaultDir)
                val validItems = mutableListOf<VaultItem>()
                var changed = false

                metadata.forEach { (id, item) ->
                    val file = File(vaultDir, id)
                    if (file.exists()) {
                        validItems.add(item)
                    } else {
                        changed = true
                    }
                }

                if (changed) {
                    val cleanMap = validItems.associateBy { it.id }
                    saveMetadata(vaultDir, cleanMap)
                }
                items.addAll(validItems)
            }
        }
        return items.sortedByDescending { it.dateAdded }
    }

    fun getVaultFile(context: Context, item: VaultItem): File {
        val binDir = if (item.vaultPath != null) File(item.vaultPath) else getVaultDirs(context).first()
        return File(binDir, item.id)
    }

    private fun getNonConflictingTarget(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile ?: return file
        val name = file.name
        val dotIndex = name.lastIndexOf('.')
        val base = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = File(parent, "$base ($counter)$ext")
            if (!candidate.exists()) {
                return candidate
            }
            counter++
        }
    }

    fun cleanIncompleteOperations(context: Context) {
        getVaultDirs(context).forEach { dir ->
            dir.listFiles { _, name -> name.contains(".tmp-") }?.forEach {
                runCatching { it.delete() }
            }
        }
    }

    // -------------------------------------------------------------------------
    // PIN Setup & Verification (Salted SHA-256 + Shared Vault Config Backup)
    // -------------------------------------------------------------------------

    fun isPinSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = prefs.getString(KEY_PIN_HASH, null)
        if (!hash.isNullOrEmpty()) return true

        // Check fallback config in vault dir (in case app data was cleared)
        return try {
            val primaryDir = getVaultDirs(context).first()
            val configFile = File(primaryDir, VAULT_CONFIG_FILE)
            if (configFile.exists()) {
                val text = configFile.readText()
                val json = JSONObject(text)
                val backupHash = json.optString("hash")
                val backupSalt = json.optString("salt")
                if (backupHash.isNotBlank() && backupSalt.isNotBlank()) {
                    prefs.edit()
                        .putString(KEY_PIN_HASH, backupHash)
                        .putString(KEY_PIN_SALT, backupSalt)
                        .apply()
                    true
                } else false
            } else false
        } catch (e: Exception) {
            false
        }
    }

    fun setupPin(context: Context, pin: String): Boolean {
        if (pin.length < 4) return false
        return try {
            val random = SecureRandom()
            val saltBytes = ByteArray(16)
            random.nextBytes(saltBytes)
            val salt = bytesToHex(saltBytes)
            val hash = hashPin(pin, salt)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PIN_HASH, hash)
                .putString(KEY_PIN_SALT, salt)
                .apply()

            // Backup in .vault_config on storage
            getVaultDirs(context).firstOrNull()?.let { vaultDir ->
                runCatching {
                    val configFile = File(vaultDir, VAULT_CONFIG_FILE)
                    val json = JSONObject().apply {
                        put("hash", hash)
                        put("salt", salt)
                    }
                    configFile.writeText(json.toString())
                }
            }

            isUnlocked = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var hash = prefs.getString(KEY_PIN_HASH, null)
        var salt = prefs.getString(KEY_PIN_SALT, null)

        if (hash == null || salt == null) {
            // Try recover from backup config
            val primaryDir = getVaultDirs(context).first()
            val configFile = File(primaryDir, VAULT_CONFIG_FILE)
            if (configFile.exists()) {
                runCatching {
                    val json = JSONObject(configFile.readText())
                    hash = json.optString("hash")
                    salt = json.optString("salt")
                    if (!hash.isNullOrEmpty() && !salt.isNullOrEmpty()) {
                        prefs.edit()
                            .putString(KEY_PIN_HASH, hash)
                            .putString(KEY_PIN_SALT, salt)
                            .apply()
                    }
                }
            }
        }

        if (hash == null || salt == null) return false
        val computed = hashPin(pin, salt!!)
        val matches = computed == hash
        if (matches) {
            isUnlocked = true
        }
        return matches
    }

    fun changePin(context: Context, oldPin: String, newPin: String): Boolean {
        if (!verifyPin(context, oldPin)) return false
        return setupPin(context, newPin)
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray(Charsets.UTF_8))
        val digest = md.digest(pin.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
