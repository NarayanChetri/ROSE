package dev.narayan.rose

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
}
