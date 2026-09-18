package com.excp.podroid.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.RandomAccessFile

class PersistentStorageTest {
    @Test fun growthAndShrinkRequestsPreserveExistingBytes() {
        val directory = Files.createTempDirectory("podroid-storage").toFile()
        try {
            val disk = PersistentStorage.ensure(directory, 4096)
            assertEquals(" podroid.storage.new=1", PersistentStorage.formatParameter(directory))
            RandomAccessFile(disk, "rw").use { it.writeUTF("persistent-user-data") }
            PersistentStorage.markReady(directory)
            PersistentStorage.ensure(directory, 8192)
            assertEquals(8192, disk.length())
            PersistentStorage.ensure(directory, 1024)
            assertEquals(8192, disk.length())
            RandomAccessFile(disk, "r").use { assertEquals("persistent-user-data", it.readUTF()) }
            assertEquals("", PersistentStorage.formatParameter(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun existingDiskIsNeverMarkedForFormatting() {
        val directory = Files.createTempDirectory("podroid-existing").toFile()
        try {
            java.io.File(directory, "storage.img").writeText("existing")
            PersistentStorage.ensure(directory, 4096)
            assertEquals("", PersistentStorage.formatParameter(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
