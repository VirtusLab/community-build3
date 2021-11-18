package com.virtuslab.communitybuild.mvnrepo.dependency.resolver.model

abstract class DependencyInfo(
    fullFilename: String,
    filename: String,
    open val organization: String,
    open val name: String,
    open val version: String,
    open val extension: String,
    open val type: String,
    mappedTo: DependencyInfo?
) : FileInfo(fullFilename, filename, mappedTo)