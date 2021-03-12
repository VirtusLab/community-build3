package com.arturwegrzyn.springmavenrepository.controller

fun isInfoPage(parameterMap: Map<String, Array<String>>) = parameterMap.contains("info")

fun isDirectoryName(filename: String): Boolean {
    return !isFileName(filename)
}

fun isFileName(filename: String): Boolean {
    val filenameWithoutSlashAtTheEnd = filename.dropLastWhile { it == '/' }

    return arrayOf(".jar", ".pom", ".xml", ".sha1", ".md5", ".asc")
            .any { filenameWithoutSlashAtTheEnd.endsWith(it) }
}

fun isScalaDependency(fullFilename: String): Boolean {
    val filenameWithoutSlashAtTheEnd = fullFilename.dropLastWhile { it == '/' }
    val match = Regex("^([a-z/]*)/(.*)_([0-9A-Za-z.-]*)/([0-9.]*)/(.*)_([0-9A-Za-z.-]*)-([0-9.]*)\\.([a-z]*)\$").find(filenameWithoutSlashAtTheEnd)
            ?: return false
    val (organization, name1, scalaVersion1, version1, name2, scalaVersion2, version2, extension) = match.destructured
    return isFileName(fullFilename) && name1 == name2 && scalaVersion1 == scalaVersion2 && version1 == version2
}
