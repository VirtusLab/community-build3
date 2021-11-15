package com.virtuslab.communitybuild.mvnrepo.dependency.resolver

import com.virtuslab.communitybuild.mvnrepo.dependency.resolver.model.FileInfo
import com.virtuslab.communitybuild.mvnrepo.exception.NoDependencyInfoMatchedException
import org.slf4j.LoggerFactory

abstract class FileInfoResolver(internal val next: FileInfoResolver?) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(fullFilename: String): FileInfo = resolve(fullFilename, null)
    fun resolve(fullFilename: String, mappedTo: FileInfo?): FileInfo = resolve(fullFilename, mappedTo, emptyList())
    abstract fun resolve(fullFilename: String, mappedTo: FileInfo?, exceptions: List<Pair<Class<out FileInfoResolver>,Exception>>): FileInfo
    internal fun next(fullFilename: String, mappedTo: FileInfo?, exceptions: List<Pair<Class<out FileInfoResolver>,Exception>>): FileInfo {
        return next?.resolve(fullFilename, mappedTo, exceptions) ?: throw NoDependencyInfoMatchedException(fullFilename, exceptions)
    }
}