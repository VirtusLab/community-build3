package com.arturwegrzyn.springmavenrepository.dependency.resolver;

import com.arturwegrzyn.springmavenrepository.dependency.isDirectoryName
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.DirectoryInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.JavaDependencyInfo
import com.arturwegrzyn.springmavenrepository.exception.ItIsNotDirectoryException
import java.lang.Exception

class DirectoryInfoResolver(next: FileInfoResolver?) : FileInfoResolver(next) {

    override fun resolve(
        fullFilename: String,
        mappedTo: FileInfo?,
        exceptions: List<Pair<Class<out FileInfoResolver>, Exception>>
    ): FileInfo {
        try {

            val filenameWithoutSlashAtTheEnd = fullFilename.dropLastWhile { it == '/' }

            if (isDirectoryName(filenameWithoutSlashAtTheEnd) && mappedTo is DirectoryInfo?) {
                val splittedFullFilename = filenameWithoutSlashAtTheEnd.split("/")
                val filename = splittedFullFilename.last()

                return DirectoryInfo(filenameWithoutSlashAtTheEnd, filename, mappedTo)
            }
        } catch (exception: Exception) {
            val newExceptions = exceptions.plusElement(Pair(javaClass, exception))
            next(fullFilename, mappedTo, newExceptions)
        }

        return next(fullFilename, mappedTo, exceptions)
    }
}