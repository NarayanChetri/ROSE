package dev.narayan.rose

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PrivateVaultManagerTest {

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray(Charsets.UTF_8))
        val digest = md.digest(pin.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    @Test
    fun testPinHashingConsistency() {
        val pin = "1234"
        val salt = "abcdef0123456789"

        val hash1 = hashPin(pin, salt)
        val hash2 = hashPin(pin, salt)

        assertEquals(hash1, hash2)
        assertNotEquals(pin, hash1)
    }

    @Test
    fun testWrongPinProducesDifferentHash() {
        val pin1 = "1234"
        val pin2 = "1235"
        val salt = "abcdef0123456789"

        val hash1 = hashPin(pin1, salt)
        val hash2 = hashPin(pin2, salt)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun testDifferentSaltProducesDifferentHash() {
        val pin = "5678"
        val salt1 = "1111222233334444"
        val salt2 = "5555666677778888"

        val hash1 = hashPin(pin, salt1)
        val hash2 = hashPin(pin, salt2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun testVaultItemProperties() {
        val item = VaultItem(
            id = "test-uuid.vault",
            originalName = "my_private_video.mp4",
            originalPath = "/storage/emulated/0/DCIM/Camera/my_private_video.mp4",
            dateAdded = 1700000000000L,
            originalLastModified = 1699999000000L,
            size = 10485760L,
            mimeType = "video/mp4",
            fileType = FileType.VIDEO,
            vaultPath = "/storage/emulated/0/.rose_vault"
        )

        assertEquals("test-uuid.vault", item.id)
        assertEquals("my_private_video.mp4", item.originalName)
        assertEquals(FileType.VIDEO, item.fileType)
        assertEquals(10485760L, item.size)
        assertEquals("video/mp4", item.mimeType)
        assertTrue(item.id.endsWith(".vault"))
    }
}
