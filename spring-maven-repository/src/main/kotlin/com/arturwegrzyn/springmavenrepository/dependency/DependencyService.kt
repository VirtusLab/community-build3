package com.arturwegrzyn.springmavenrepository.dependency

import com.arturwegrzyn.springmavenrepository.dependency.model.*
import com.arturwegrzyn.springmavenrepository.dependency.resolver.DirectoryInfoResolver
import com.arturwegrzyn.springmavenrepository.dependency.resolver.JavaDependencyInfoResolver
import com.arturwegrzyn.springmavenrepository.dependency.resolver.ScalaDependencyInfoResolver
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.ScalaDependencyInfo
import com.arturwegrzyn.springmavenrepository.exception.StorageFileNotFoundWithFileNameException
import com.arturwegrzyn.springmavenrepository.storage.StorageService
import org.springframework.core.env.Environment
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.io.File
import java.io.InputStream
import java.net.URI

@Service
class DependencyService(private val storageService: StorageService, private val env: Environment) {
    companion object {
        val resolvers = ScalaDependencyInfoResolver(JavaDependencyInfoResolver(DirectoryInfoResolver(null)))
    }

    fun uploadDependency(fullFileName: String, inputStream: InputStream) {
        storageService.store(fullFileName, inputStream)
    }

    fun getInfoAbout(filename: String): ResponseEntity<FileInfo> {
        return try {
            val resourceWithInfo = fetchDependency(filename)
            ResponseEntity.ok().body(resourceWithInfo.first)
        } catch (e: StorageFileNotFoundWithFileNameException) {
            ResponseEntity.noContent().build()
        }
    }

    fun getDirectory(dir: String): Directory {
        val pathList = storageService.loadAllFromDir(dir)
        val builder = Directory.builder(URI.create(dir))
        pathList.forEach { path ->
            val file = path!!.toFile()
            builder.add(FileContext(file.name, file.length(), file.lastModified(), file.isDirectory))
        }
        return builder.build()
    }

    fun fetchDependency(filename: String): Pair<FileInfo, Resource> {

        val demandedFileInfo = filenameToFileInfo(filename)

        return loadDependency(demandedFileInfo)
    }

    private fun filenameToFileInfo(filename: String): FileInfo {
        return resolvers.resolve(storageService.standarizeFilename(filename))
    }

    private fun loadDependency(demandedFileInfo: FileInfo): Pair<FileInfo, Resource> {
        return when (demandedFileInfo) {
            is ScalaDependencyInfo -> loadScalaDependency(demandedFileInfo)
            else -> Pair(demandedFileInfo, storageService.loadAsResource(demandedFileInfo.fullFilename))
        }
    }

    private fun loadScalaDependency(demandedScalaDependencyInfo: ScalaDependencyInfo): Pair<FileInfo, Resource> {
        try {
            val fullFilename = demandedScalaDependencyInfo.fullFilename
            return Pair(demandedScalaDependencyInfo, storageService.loadAsResource(fullFilename))

        } catch (e: StorageFileNotFoundWithFileNameException) {

            val demandedScalaDependencyInfoWithTargetScalaVersion = mapToTargetScalaVersion(demandedScalaDependencyInfo)
            val dependencyPath = demandedScalaDependencyInfoWithTargetScalaVersion.getDependencyPath()
            val dependencyDir = storageService.load(dependencyPath).toFile()

            return listFilesFromFirstMatchingDirectory(dependencyDir) {
                versionWithoutPatchPart(it.name) == versionWithoutPatchPart(
                    demandedScalaDependencyInfoWithTargetScalaVersion.version
                )
            }
                .map { Pair(filenameToFileInfo(it.path) as ScalaDependencyInfo, it) }
                .filter {
                    it.first.extension == demandedScalaDependencyInfoWithTargetScalaVersion.extension &&
                            it.first.scalaVersion == demandedScalaDependencyInfoWithTargetScalaVersion.scalaVersion &&
                            it.first.jarType == demandedScalaDependencyInfoWithTargetScalaVersion.jarType
                }
                .map { Pair(it.first, UrlResource(it.second.toPath().toUri())) }
                .filter { it.second.exists() && it.second.isReadable }
                .map { Pair(demandedScalaDependencyInfo.copy(mappedTo = it.first), it.second) }
                .firstOrNull()
                ?: throw StorageFileNotFoundWithFileNameException(
                    demandedScalaDependencyInfo.fullFilename,
                    "Could not read file: ${demandedScalaDependencyInfo.fullFilename}"
                )
        }
    }

    private fun versionWithoutPatchPart(version: String) = version.split(".").dropLast(1)

    private fun mapToTargetScalaVersion(demandedScalaDependencyInfo: ScalaDependencyInfo): ScalaDependencyInfo {
        val targetScalaVersion = env.getProperty("maven.scala.targetVersion")

        return if (targetScalaVersion != null) {
            demandedScalaDependencyInfo.copy(scalaVersion = targetScalaVersion)
        } else {
            demandedScalaDependencyInfo
        }
    }

    private fun listFilesFromFirstMatchingDirectory(dependencyDir: File, predicate: (File) -> Boolean): Array<File> {
        return if (dependencyDir.isDirectory && dependencyDir.exists())
            dependencyDir.listFiles()
                ?.firstOrNull { predicate(it) }
                ?.listFiles()
                ?: emptyArray()
        else emptyArray()
    }
}