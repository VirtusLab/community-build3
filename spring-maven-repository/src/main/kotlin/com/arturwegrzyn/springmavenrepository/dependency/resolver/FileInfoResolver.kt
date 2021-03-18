package com.arturwegrzyn.springmavenrepository.dependency.resolver

import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.FileInfo
import com.arturwegrzyn.springmavenrepository.exception.NoDependencyInfoMatchedException

abstract class FileInfoResolver(internal val next: FileInfoResolver?) {
    fun resolve(fullFilename: String): FileInfo = resolve(fullFilename, null)
    abstract fun resolve(fullFilename: String, mappedTo: FileInfo?): FileInfo
    internal fun next(fullFilename: String, mappedTo: FileInfo?): FileInfo {
        return (next ?: throw NoDependencyInfoMatchedException(fullFilename))
            .resolve(fullFilename, mappedTo)
    }
}