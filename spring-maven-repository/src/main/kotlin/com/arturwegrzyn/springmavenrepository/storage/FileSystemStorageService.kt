package com.arturwegrzyn.springmavenrepository.storage

import com.arturwegrzyn.springmavenrepository.model.ScalaDependencyInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.stereotype.Service
import org.springframework.util.FileSystemUtils
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.Objects.*
import java.util.stream.Collectors

@Service
class FileSystemStorageService @Autowired constructor(val properties: StorageProperties) : StorageService {
    private val rootLocation: Path = Paths.get(properties.location)
    private val log = LoggerFactory.getLogger(javaClass)

    override fun store(file: MultipartFile) {
        try {
            if (file.isEmpty) {
                throw StorageException("Failed to store empty file " + file.originalFilename)
            }
            Files.copy(file.inputStream, rootLocation.resolve(file.originalFilename))
        } catch (e: IOException) {
            throw StorageException("Failed to store file " + file.originalFilename, e)
        }
    }

    override fun store(fullFileName: String, inputStream: InputStream) {
        try {
            val pathToFile = load(fullFileName).toAbsolutePath()
            createDirectories(pathToFile.parent)
            Files.copy(inputStream, pathToFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw StorageException("Failed to store file $fullFileName", e)
        }
    }

    override fun loadAll(): List<Path> {
        return try {
            Files.walk(rootLocation, 1)
                    .filter { path: Path -> path != rootLocation }
                    .map { path: Path -> rootLocation.relativize(path) }
                    .collect(Collectors.toList())
        } catch (e: IOException) {
            throw StorageException("Failed to read stored files", e)
        }
    }

    override fun load(filename: String): Path {
        return rootLocation.resolve(filename)
    }

    override fun loadAsResource(filename: String): Pair<ScalaDependencyInfo, Resource> {
        val file = load(filename)

        val resource: Resource = UrlResource(file.toUri())
        return if (resource.exists() && resource.isReadable) {
            Pair(filenameToScalaDependencyInfo(filename, properties.location), resource)
        } else {
            findBackupResource(filename)
        }
    }

    private fun findBackupResource(filename: String): Pair<ScalaDependencyInfo, Resource> {
        val scalaDependencyInfo = filenameToScalaDependencyInfo(filename, properties.location)
        val versionWithoutPatch = scalaDependencyInfo.version.split(".").dropLast(1)
        val dependencyDir = load(scalaDependencyInfo.getDependencyPath()).toFile()

        return listFilesFromFirstMatchingDirectory(dependencyDir) { it.name.split(".").dropLast(1) == versionWithoutPatch }
                .map { Pair(filenameToScalaDependencyInfo(it.path, properties.location), it) }
                .filter { it.first.extension == scalaDependencyInfo.extension && it.first.scalaVersion == scalaDependencyInfo.scalaVersion && it.first.jarType == scalaDependencyInfo.jarType }
                .map { Pair(it.first,UrlResource(it.second.toPath().toUri())) }
                .filter { it.second.exists() && it.second.isReadable }
                .map { Pair(filenameToScalaDependencyInfo(filename, it.first, properties.location), it.second) }
                .firstOrNull()
                ?: throw StorageFileNotFoundWithFileNameException(filename, "Could not read file: $filename")
    }

    private fun listFilesFromFirstMatchingDirectory(dependencyDir: File, predicate: (File) -> Boolean): Array<File> {
        return if (dependencyDir.isDirectory && dependencyDir.exists())
            dependencyDir.listFiles()
                    ?.firstOrNull { predicate(it) }
                    ?.listFiles()
                    ?: emptyArray()
        else emptyArray()
    }


    override fun loadAllFromDir(dirName: String): List<Path> {
        return try {
            Files.walk(rootLocation.resolve(dirName), 1)
                    .filter { path: Path -> path != rootLocation.resolve(dirName) }
                    .collect(Collectors.toList())
        } catch (e: IOException) {
            throw StorageException("Failed to read stored files", e)
        }
    }

    override fun loadAllFromDirAsResource(filename: String): List<Resource> {
        return loadAllFromDir(filename).stream()
                .map { obj: Path -> obj.toUri() }
                .map { uri: URI ->
                    try {
                        return@map UrlResource(uri)
                    } catch (e: MalformedURLException) {
                        throw StorageFileNotFoundWithFileNameException(filename, "Could not read file: $filename", e)
                    }
                }
                .filter { resource: UrlResource -> resource.exists() || resource.isReadable }
                .collect(Collectors.toList())
    }

    override fun deleteAll() {
        FileSystemUtils.deleteRecursively(rootLocation.toFile())
    }

    override fun isDirectory(filename: String): Boolean {
        return load(filename).toFile().isDirectory
    }

    override fun init() {
        try {
            if (Files.notExists(rootLocation)) {
                Files.createDirectory(rootLocation)
            }
        } catch (e: IOException) {
            throw StorageException("Could not initialize storage", e)
        }
    }

    init {
        log.info("Main storage path {}.", rootLocation.toAbsolutePath())
    }
}