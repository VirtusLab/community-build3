package com.arturwegrzyn.springmavenrepository.dependency.resolver.model

abstract class DependencyInfo(
    fullFilename: String,
    filename: String,
    open val organization: String,
    open val name: String,
    open val version: String,
    open val extension: String,
    mappedTo: DependencyInfo?
) : FileInfo(fullFilename, filename, mappedTo)