package com.virtuslab.communitybuild.mvnrepo.storage

import java.nio.file.Path

fun createDirectories(directory: Path): Boolean {
    return directory.toFile().mkdirs()
}