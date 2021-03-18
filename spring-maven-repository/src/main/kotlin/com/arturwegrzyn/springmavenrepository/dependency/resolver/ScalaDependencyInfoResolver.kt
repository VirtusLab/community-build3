package com.arturwegrzyn.springmavenrepository.dependency.resolver

import com.arturwegrzyn.springmavenrepository.dependency.isFileName
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.ScalaDependencyInfo

class ScalaDependencyInfoResolver(next: FileInfoResolver?) : FileInfoResolver(next) {

    private val SCALA_FULL_FILENAME_REGEX =
        Regex("^([a-z/]*)/(.*)_([0-9A-Za-z.-]*)/([0-9]*\\.[0-9]*\\.[0-9]*)/(.*)_([0-9A-Za-z.-]*)-([0-9]*\\.[0-9]*\\.[0-9]*)(-)?(.*)?\\.([a-z0-9]{1,4})\$")

    override fun resolve(fullFilename: String, mappedTo: FileInfo?): FileInfo {
        val filenameWithoutSlashAtTheEnd = fullFilename.dropLastWhile { it == '/' }
        val match = SCALA_FULL_FILENAME_REGEX.find(filenameWithoutSlashAtTheEnd)
        if (match != null) {
            val (organization, name1, scalaVersion1, version1, name2, scalaVersion2, version2, jarType_IGNORE_IT, jarType, extension) = match.destructured
            if (isScalaDependency(
                    filenameWithoutSlashAtTheEnd,
                    name1,
                    name2,
                    scalaVersion1,
                    scalaVersion2,
                    version1,
                    version2
                ) && mappedTo is ScalaDependencyInfo?
            ) {
                val splittedFullFilename = filenameWithoutSlashAtTheEnd.split("/")
                val filename = splittedFullFilename.last()

                return ScalaDependencyInfo(
                    filenameWithoutSlashAtTheEnd,
                    filename,
                    organization.replace("/","."),
                    name1,
                    version1,
                    extension,
                    scalaVersion1,
                    jarType,
                    mappedTo
                )
            }
        }
        return next(fullFilename, mappedTo)

    }

    private fun isScalaDependency(
        filename: String,
        name1: String,
        name2: String,
        scalaVersion1: String,
        scalaVersion2: String,
        version1: String,
        version2: String
    ) = isFileName(filename) && name1 == name2 && scalaVersion1 == scalaVersion2 && version1 == version2
}