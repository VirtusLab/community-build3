package com.virtuslab.communitybuild.mvnrepo.dependency.resolver.model

data class PlainFileInfo(
    override val fullFilename: String,
    override val filename: String,
    override val mappedTo: PlainFileInfo?
) : FileInfo(fullFilename, filename, mappedTo)
