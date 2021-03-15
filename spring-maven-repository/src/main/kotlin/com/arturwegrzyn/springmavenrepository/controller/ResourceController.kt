package com.arturwegrzyn.springmavenrepository.controller

import com.arturwegrzyn.springmavenrepository.model.Directory
import com.arturwegrzyn.springmavenrepository.model.FileContext
import com.arturwegrzyn.springmavenrepository.model.ScalaDependencyInfo
import com.arturwegrzyn.springmavenrepository.storage.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.net.URI
import javax.servlet.http.HttpServletRequest


@Controller
class ResourceController @Autowired constructor(private val storageService: StorageService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/**")
    fun getDispatcher(request: HttpServletRequest, model: Model): Any {
        val filename = fullFileNameFromRequest(request)

        return when {
            isInfoPage(request.parameterMap) -> info(filename)
            isDirectoryName(filename) -> list(filename, model)
            isScalaDependency(filename) -> fetchScalaDependency(filename)
            else -> fetchOtherDependency(filename)
        }
    }

    private fun info(filename: String): ResponseEntity<ScalaDependencyInfo> {
        log.debug("GET info $filename")
        return try {
            val resourceWithInfo = storageService.loadAsResource(filename, true)
            ResponseEntity.ok().body(resourceWithInfo.first)
        } catch (e: StorageFileNotFoundWithFileNameException) {
            ResponseEntity.noContent().build()
        }
    }

    private fun list(filename: String, model: Model): String {
        log.debug("GET listFiles $filename")
        val directory = getDirectory(filename)
        model.addAttribute("directory", directory)
        return "listMustache"
    }

    private fun getDirectory(dir: String): Directory {
        val pathList = storageService.loadAllFromDir(dir)
        val builder = Directory.builder(URI.create(dir))
        pathList.forEach { path ->
            val file = path!!.toFile()
            builder.add(FileContext(file.name, file.length(), file.lastModified(), file.isDirectory))
        }
        return builder.build()
    }

    private fun fetchScalaDependency(filename: String): ResponseEntity<Resource> {
        log.debug("GET scala dependency file $filename")
        val resourceWithInfo = storageService.loadAsResource(filename, true)
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resourceWithInfo.first.filename + "\"")
                .body(resourceWithInfo.second)
    }

    private fun fetchOtherDependency(filename: String): ResponseEntity<Resource>  {
        log.debug("GET other dependency file $filename")
        val resourceWithInfo = storageService.loadAsResource(filename, false)
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resourceWithInfo.first.filename + "\"")
                .body(resourceWithInfo.second)
    }

    @PutMapping("/**")
    fun putFileDispatcher(request: HttpServletRequest): Any {
        return put(request)
    }

    private fun put(request: HttpServletRequest): ResponseEntity<Any> {
        val fullFileName = fullFileNameFromRequest(request)
        log.debug("PUT dependency file $fullFileName")
        storageService.store(fullFileName, request.inputStream)
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }


    init {
        //TODO move to configuration class
        storageService.init()
    }
}