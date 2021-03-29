package com.arturwegrzyn.springmavenrepository.dependency.resolver.model

data class DirectoryInfo(
    override val fullFilename: String,
    override val filename: String,
    override val mappedTo: DirectoryInfo?
) : FileInfo(fullFilename, filename, mappedTo)