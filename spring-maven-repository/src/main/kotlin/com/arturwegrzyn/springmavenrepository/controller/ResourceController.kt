package com.arturwegrzyn.springmavenrepository.controller

import com.arturwegrzyn.springmavenrepository.model.Directory
import com.arturwegrzyn.springmavenrepository.model.FileContext
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
        val servletPath = request.servletPath
        val lastChar = servletPath.substring(servletPath.length - 1)
        val filename = fullFileNameFromRequest(request)
        return when (lastChar) {
            "/" -> {
                list(filename, model)
            }
            else -> {
                fetch(filename)
            }
        }
    }

    @PutMapping("/**")
    fun putFileDispatcher(request: HttpServletRequest): Any {
            return put(request)
    }

    private fun list(filename: String, model: Model): String {
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

    private fun fetch(filename: String): ResponseEntity<Resource> {
        val file = storageService.loadAsResource(filename)
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename + "\"")
                .body(file)
    }

    private fun put(request: HttpServletRequest): ResponseEntity<Any> {
        val fullFileName = fullFileNameFromRequest(request)
        storageService.store(fullFileName, request.inputStream)
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }


    init {
        //TODO move to configuration class
        storageService.init()
    }
}