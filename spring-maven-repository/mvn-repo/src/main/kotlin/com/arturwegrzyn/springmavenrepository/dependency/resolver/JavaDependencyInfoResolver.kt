package com.arturwegrzyn.springmavenrepository.dependency.resolver

import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.JavaDependencyInfo
import com.arturwegrzyn.springmavenrepository.exception.IllegalDependencyPathException
import java.lang.Exception

class JavaDependencyInfoResolver(next: FileInfoResolver?) : FileInfoResolver(next) {

    override fun resolve(fullFilename: String, mappedTo: FileInfo?, exceptions:List<Pair<Class<out FileInfoResolver>,Exception>>): FileInfo {
        val filenameWithoutSlashAtTheEnd = fullFilename.dropLastWhile { it == '/' }
        return try {
            filenameToJavaDependencyInfo(filenameWithoutSlashAtTheEnd, mappedTo as JavaDependencyInfo?)
        } catch (exception: Exception) {
            val newExceptions = exceptions.plusElement(Pair(javaClass, exception))
            next(fullFilename, mappedTo, newExceptions)
        }

    }

    private fun filenameToJavaDependencyInfo(fullFilename: String, mappedTo: JavaDependencyInfo?): JavaDependencyInfo {

        val splittedFullFilename = fullFilename.split("/")
        val nameAndVersionAndTypeAndExtension = splittedFullFilename.last()

        val filename = nameAndVersionAndTypeAndExtension
        val organization = splittedFullFilename.dropLast(3).joinToString(".")
        val name = splittedFullFilename[splittedFullFilename.lastIndex - 2]
        val version = splittedFullFilename[splittedFullFilename.lastIndex - 1]
        val extension = nameAndVersionAndTypeAndExtension.split(".").last()
        val type = nameAndVersionAndTypeAndExtension.replace(name, "").replace(version, "")
            .replace(extension, "").replace("-", "").dropLastWhile { it == '.' }

        if (
            !nameAndVersionAndTypeAndExtension.contains(name) ||
            !nameAndVersionAndTypeAndExtension.contains(version)
        )
            throw IllegalDependencyPathException(fullFilename)

        return JavaDependencyInfo(
            fullFilename,
            filename,
            organization,
            name,
            version,
            extension,
            type,
            mappedTo
        )
    }
}