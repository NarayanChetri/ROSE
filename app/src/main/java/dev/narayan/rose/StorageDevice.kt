package dev.narayan.rose

import android.net.Uri

sealed class StorageDevice {
    abstract val name: String
    abstract val iconRes: Int? // Optional icon resource override

    data class Physical(
        override val name: String,
        val path: String,
        val totalBytes: Long,
        val availableBytes: Long,
        val isSdCard: Boolean = false
    ) : StorageDevice() {
        override val iconRes: Int? = null
    }

    data class Logical(
        override val name: String,
        val treeUri: Uri,
        val rootDocId: String? = null
    ) : StorageDevice() {
        override val iconRes: Int? = null
    }
}
