package com.virtuslab.communitybuild.mvnrepo.dependency

import javax.servlet.http.HttpServletRequest

fun fullFileNameFromRequest(request: HttpServletRequest) = request.servletPath.substring(1).dropLastWhile { it == '/' }

fun isInfoPage(parameterMap: Map<String, Array<String>>) = parameterMap.contains("info")

fun isDirectoryName(filename: String): Boolean {
    return !isFileName(filename)
}

fun isHeadRequest(request: HttpServletRequest) = request.method == "HEAD"

fun isFileName(filename: String): Boolean {
    val filenameWithoutSlashAtTheEnd = filename.dropLastWhile { it == '/' }

    return arrayOf(".jar", ".pom", ".xml", ".sha1", ".md5", ".asc", ".ico")
            .any { filenameWithoutSlashAtTheEnd.endsWith(it) }
}