package com.arturwegrzyn.springmavenrepository.storage

import java.nio.file.Path
import javax.servlet.http.HttpServletRequest


class StorageHelper

fun fullFileNameFromRequest(request: HttpServletRequest) = request.servletPath.substring(1)

fun fileTypeFromFileName(fileName: String): String {
    val dotIndex = fileName.lastIndexOf('.')
    return if (dotIndex == -1) "" else fileName.substring(dotIndex + 1)
}

fun createDirectories(directory: Path): Boolean {
    return directory.toFile().mkdirs()
}