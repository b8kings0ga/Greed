package com.excp.podroid.engine

import java.io.File
import java.io.RandomAccessFile

/** Shared disk lifecycle: grow in place, never shrink or recreate user data. */
object PersistentStorage {
    fun ensure(directory: File, desiredBytes: Long): File {
        require(desiredBytes > 0)
        val disk = File(directory, "storage.img")
        if (!disk.exists()) {
            File(directory, "storage.needs-format").writeText("new\n")
            RandomAccessFile(disk, "rw").use { it.setLength(desiredBytes) }
        } else if (disk.length() < desiredBytes) {
            RandomAccessFile(disk, "rw").use { it.setLength(desiredBytes) }
        }
        return disk
    }

    fun formatParameter(directory: File): String =
        if (File(directory, "storage.needs-format").exists()) " podroid.storage.new=1" else ""

    fun markReady(directory: File) {
        File(directory, "storage.needs-format").delete()
    }
}
