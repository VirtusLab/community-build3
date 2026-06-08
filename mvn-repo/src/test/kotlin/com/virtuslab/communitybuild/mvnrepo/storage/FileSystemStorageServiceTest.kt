package com.virtuslab.communitybuild.mvnrepo.storage

import com.virtuslab.communitybuild.mvnrepo.exception.StorageFileNotFoundWithFileNameException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileSystemStorageServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun service(): FileSystemStorageService =
        FileSystemStorageService(StorageProperties(tempDir.toString()))

    @Test
    fun loadAllFromDir_missingDirectoryThrowsNotFound() {
        val service = service()

        assertThrows<StorageFileNotFoundWithFileNameException> {
            service.loadAllFromDir("org/example/missing")
        }
    }

    @Test
    fun loadAllFromDir_existingDirectoryListsEntries() {
        val service = service()
        val dir = tempDir.resolve("org/example/greeter")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("maven-metadata.xml"), "<metadata/>")

        val entries = service.loadAllFromDir("org/example/greeter")

        assert(entries.size == 1)
        assert(entries[0].fileName.toString() == "maven-metadata.xml")
    }
}
