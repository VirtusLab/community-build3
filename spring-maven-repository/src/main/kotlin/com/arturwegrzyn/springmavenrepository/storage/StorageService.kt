package com.arturwegrzyn.springmavenrepository.storage

import com.arturwegrzyn.springmavenrepository.model.ScalaDependencyInfo
import org.springframework.core.io.Resource
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.nio.file.Path

interface StorageService {
    fun init()
    fun store(file: MultipartFile)
    fun store(fullFileName: String, inputStream: InputStream)
    fun loadAll(): List<Path>
    fun load(filename: String): Path
    fun loadAsResource(filename: String): Pair<ScalaDependencyInfo, Resource>
    fun loadAllFromDir(dirName: String): List<Path?>
    fun loadAllFromDirAsResource(filename: String): List<Resource?>
    fun deleteAll()
    fun isDirectory(filename:String): Boolean
}