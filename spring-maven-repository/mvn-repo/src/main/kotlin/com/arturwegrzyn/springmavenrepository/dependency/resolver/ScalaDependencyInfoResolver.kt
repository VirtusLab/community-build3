package com.arturwegrzyn.springmavenrepository.dependency.resolver

import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.ScalaDependencyInfo
import com.arturwegrzyn.springmavenrepository.exception.IllegalDependencyPathException

class ScalaDependencyInfoResolver(next: FileInfoResolver?) : FileInfoResolver(next) {

    override fun resolve(
        fullFilename: String,
        mappedTo: FileInfo?,
        exceptions: List<Pair<Class<out FileInfoResolver>, Exception>>
    ): FileInfo {
        val filenameWithoutSlashAtTheEnd = fullFilename.dropLastWhile { it == '/' }
        return try {
            filenameToScalaDependencyInfo(filenameWithoutSlashAtTheEnd, mappedTo as ScalaDependencyInfo?)
        } catch (exception: Exception) {
            val newExceptions = exceptions.plusElement(Pair(javaClass, exception))
            next(fullFilename, mappedTo, newExceptions)
        }
    }

    private fun filenameToScalaDependencyInfo(
        fullFilename: String,
        mappedTo: ScalaDependencyInfo?
    ): ScalaDependencyInfo {

        val splittedFullFilename = fullFilename.split("/")
        val nameAndScalaVersionAndVersionAndTypeAndExtension = splittedFullFilename.last()
        val nameAndScalaVersion = splittedFullFilename[splittedFullFilename.lastIndex - 2]

        val organization = splittedFullFilename.dropLast(3).joinToString(".")
        val name = nameAndScalaVersion.split("_")[0]
        val filename = nameAndScalaVersionAndVersionAndTypeAndExtension
        val scalaVersion = nameAndScalaVersion.split("_")[1]
        val version = splittedFullFilename[splittedFullFilename.lastIndex - 1]
        val extension = nameAndScalaVersionAndVersionAndTypeAndExtension.split(".").last()
        val type =
            nameAndScalaVersionAndVersionAndTypeAndExtension.replace(name, "").replace(scalaVersion, "")
                .replace(version, "").replace(extension, "").replace("-", "").replace("_", "")
                .dropLastWhile { it == '.' }


        if (
            !nameAndScalaVersionAndVersionAndTypeAndExtension.contains(name) ||
            !nameAndScalaVersionAndVersionAndTypeAndExtension.contains(version)
        )
            throw IllegalDependencyPathException(fullFilename)


        return ScalaDependencyInfo(
            fullFilename,
            filename,
            organization,
            name,
            version,
            extension,
            scalaVersion,
            type,
            mappedTo
        )
    }
}