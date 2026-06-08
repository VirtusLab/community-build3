package com.virtuslab.communitybuild.mvnrepo.dependency.resolver

import com.virtuslab.communitybuild.mvnrepo.dependency.isFileName
import com.virtuslab.communitybuild.mvnrepo.dependency.resolver.model.FileInfo
import com.virtuslab.communitybuild.mvnrepo.dependency.resolver.model.PlainFileInfo

class PlainFileInfoResolver(next: FileInfoResolver?) : FileInfoResolver(next) {

    override fun resolve(
        fullFilename: String,
        mappedTo: FileInfo?,
        exceptions: List<Pair<Class<out FileInfoResolver>, Exception>>
    ): FileInfo {
        val normalized = fullFilename.dropLastWhile { it == '/' }
        if (isFileName(normalized)) {
            val filename = normalized.split("/").last()
            return PlainFileInfo(normalized, filename, mappedTo as PlainFileInfo?)
        }
        return next(fullFilename, mappedTo, exceptions)
    }
}
