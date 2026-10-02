package dev.narayan.rose

sealed class StorageDevice {
    abstract val name: String
    abstract val path: String
    abstract val totalBytes: Long
    abstract val availableBytes: Long
    abstract val iconRes: Int? // Optional icon resource override

    data class Physical(
        override val name: String,
        override val path: String,
        override val totalBytes: Long,
        override val availableBytes: Long,
        val isSdCard: Boolean = false
    ) : StorageDevice() {
        override val iconRes: Int? = null
    }

    data class Root(
        override val name: String = "Root",
        override val path: String = "/",
        override val totalBytes: Long = 0L,
        override val availableBytes: Long = 0L
    ) : StorageDevice() {
        override val iconRes: Int? = null
    }
}
