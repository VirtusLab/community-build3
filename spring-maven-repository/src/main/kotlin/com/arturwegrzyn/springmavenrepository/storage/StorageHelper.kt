package com.arturwegrzyn.springmavenrepository.storage

import com.arturwegrzyn.springmavenrepository.model.ScalaDependencyInfo
import java.nio.file.Path
import javax.servlet.http.HttpServletRequest


fun fullFileNameFromRequest(request: HttpServletRequest) = request.servletPath.substring(1).dropLastWhile { it == '/' }

fun fileTypeFromFileName(fileName: String): String {
    val dotIndex = fileName.lastIndexOf('.')
    return if (dotIndex == -1) "" else fileName.substring(dotIndex + 1)
}

fun createDirectories(directory: Path): Boolean {
    return directory.toFile().mkdirs()
}

fun filenameToScalaDependencyInfo(fullFilename: String, storageLocation: String): ScalaDependencyInfo {
    return filenameToScalaDependencyInfo(fullFilename, null, storageLocation)
}

fun filenameToScalaDependencyInfo(fullFilename: String, mappedTo: ScalaDependencyInfo?, storageLocation: String): ScalaDependencyInfo {

    val splittedFullFilename = fullFilename.split("/").dropWhile { it == storageLocation }
    val nameAndScalaVersionAndVersionAndTypeAndExtension = splittedFullFilename.last()
    val nameAndScalaVersion = splittedFullFilename[splittedFullFilename.lastIndex - 2]

    val organization = splittedFullFilename.dropLast(3).joinToString(".")
    val name = nameAndScalaVersion.split("_")[0]
    val filename = nameAndScalaVersionAndVersionAndTypeAndExtension
    val scalaVersion = nameAndScalaVersion.split("_")[1]
    val version = splittedFullFilename[splittedFullFilename.lastIndex - 1]
    val type = getJarType(nameAndScalaVersionAndVersionAndTypeAndExtension)
    val extension = nameAndScalaVersionAndVersionAndTypeAndExtension.split(".").last()
    return ScalaDependencyInfo(organization, name, filename, scalaVersion, nameAndScalaVersion, version, type, extension, mappedTo)
}

private fun getJarType(nameAndScalaVersionAndVersionAndTypeAndExtension: String): String {
    if (nameAndScalaVersionAndVersionAndTypeAndExtension.contains("javadoc"))
        return "javadoc"
    else if (nameAndScalaVersionAndVersionAndTypeAndExtension.contains("sources"))
        return "sources"
    return ""
}