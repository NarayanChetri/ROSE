package dev.narayan.rose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FolderSizeTest {

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", formatFileSize(0L))
        assertEquals("512.0 B", formatFileSize(512L))
        assertEquals("1.0 KB", formatFileSize(1024L))
        assertEquals("1.5 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", formatFileSize((2L * 1024 * 1024 * 1024)))
    }

    @Test
    fun testFileItemDirectoryFlag() {
        val dummyFolder = File("testFolder")
        val item = FileItem(
            file = dummyFolder,
            isDirectory = true,
            name = "testFolder",
            size = 1048576L,
            lastModified = 1000L,
            extension = ""
        )
        assertTrue(item.isDirectory)
        assertEquals(1048576L, item.size)
        assertEquals(FileType.FOLDER, item.fileType)
    }
}

