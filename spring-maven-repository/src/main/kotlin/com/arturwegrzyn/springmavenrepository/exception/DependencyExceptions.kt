package com.arturwegrzyn.springmavenrepository.exception

import java.lang.RuntimeException


open class DependencyException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}

class NoDependencyInfoMatchedException(filename: String, cause: Throwable?) : DependencyException("It is not possible to resolve $filename by any resolver!", cause) {
    constructor(filename: String) : this(filename, null)
}
