package com.arturwegrzyn.springmavenrepository.dependency.resolver.model

abstract class FileInfo(
    open val fullFilename: String,
    open val filename: String,
    open val mappedTo: FileInfo?
)