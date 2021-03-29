package com.arturwegrzyn.springmavenrepository.exception

import com.arturwegrzyn.springmavenrepository.dependency.resolver.FileInfoResolver
import java.lang.RuntimeException


open class DependencyException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}

class NoDependencyInfoMatchedException(filename: String, val exceptions: List<Pair<Class<out FileInfoResolver>, Exception>>) : DependencyException("It is not possible to resolve $filename by any resolver! ") {
}

class IllegalDependencyPathException(filename: String, cause: Throwable?) : DependencyException("Filename contains inconsistent data (name, version)! $filename", cause) {
    constructor(filename: String) : this(filename, null)
}


class ItIsNotDirectoryException(filename: String, cause: Throwable?) : DependencyException("This is not directory! $filename", cause) {
    constructor(filename: String) : this(filename, null)
}
