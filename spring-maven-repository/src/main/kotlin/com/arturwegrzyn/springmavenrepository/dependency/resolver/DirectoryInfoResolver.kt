package com.arturwegrzyn.springmavenrepository.dependency.resolver;

import com.arturwegrzyn.springmavenrepository.dependency.isDirectoryName
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.DirectoryInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo

class DirectoryInfoResolver(next: FileInfoResolver?) : FileInfoResolver(next) {

    override fun resolve(fullFilename: String, mappedTo: FileInfo?): FileInfo {
        val filenameWithoutSlashAtTheEnd = fullFilename.dropLastWhile { it == '/' }

        if (isDirectoryName(filenameWithoutSlashAtTheEnd) && mappedTo is DirectoryInfo?) {
            val splittedFullFilename = filenameWithoutSlashAtTheEnd.split("/")
            val filename = splittedFullFilename.last()

            return DirectoryInfo(filenameWithoutSlashAtTheEnd, filename, mappedTo)
        }
        return next(fullFilename, mappedTo)
    }
}