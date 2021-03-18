package com.arturwegrzyn.springmavenrepository.dependency.resolver

import com.arturwegrzyn.springmavenrepository.dependency.isFileName
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.JavaDependencyInfo

class JavaDependencyInfoResolver(next: FileInfoResolver?) : FileInfoResolver(next) {

    private val JAVA_FULL_FILENAME_REGEX = Regex("^([a-z/.-]*)/([a-z.-]*)/([0-9.]*)/([a-z.-]*)-([0-9.]*)(-(.*))?\\.([a-z]*)\$")


    override fun resolve(fullFilename: String, mappedTo: FileInfo?): FileInfo {
        val filenameWithoutSlashAtTheEnd = fullFilename.dropLastWhile { it == '/' }
        val match = JAVA_FULL_FILENAME_REGEX.find(filenameWithoutSlashAtTheEnd)
        if (match != null) {
            val (organization, name1, version1, name2, version2, jarType_IGNORE_IT, jarType, extension) = match.destructured
            if (isJavaDependency(
                    filenameWithoutSlashAtTheEnd,
                    name1,
                    name2,
                    version1,
                    version2
                ) && mappedTo is JavaDependencyInfo?
            ) {
                val splittedFullFilename = filenameWithoutSlashAtTheEnd.split("/")
                val filename = splittedFullFilename.last()

                return JavaDependencyInfo(
                    filenameWithoutSlashAtTheEnd,
                    filename,
                    organization,
                    name1,
                    version1,
                    extension,
                    mappedTo,
                    jarType
                )
            }

        }
        return next(fullFilename, mappedTo)
    }

    private fun isJavaDependency(
        filename: String,
        name1: String,
        name2: String,
        version1: String,
        version2: String
    ) = isFileName(filename) && name1 == name2 && version1 == version2

}