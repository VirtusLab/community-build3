package com.virtuslab.communitybuild.mvnrepo.exception

import com.virtuslab.communitybuild.mvnrepo.dependency.resolver.FileInfoResolver
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

class DependencyNotFoundException(val filename: String, val redirectIfNotExist:Boolean, message: String, cause: Throwable?): DependencyException(message,cause) {
    constructor(filename: String,  redirectIfNotExist:Boolean, message: String) : this(filename, redirectIfNotExist, message, null)
}