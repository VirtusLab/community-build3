package com.arturwegrzyn.springmavenrepository.storage

open class StorageException : RuntimeException {
    constructor(message: String) : super(message) {}
    constructor(message: String, cause: Throwable?) : super(message, cause) {}
}

class StorageFileNotFoundWithFileNameException(val filename: String, message: String, cause: Throwable?) : StorageException(message, cause) {
    constructor(filename: String, message: String) : this(filename, message, null)
}
