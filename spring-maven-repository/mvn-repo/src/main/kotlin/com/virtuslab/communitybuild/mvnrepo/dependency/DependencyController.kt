package com.virtuslab.communitybuild.mvnrepo.dependency

import com.virtuslab.communitybuild.mvnrepo.dependency.resolver.model.FileInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest


@Controller
class DependencyController @Autowired constructor(private val dependencyService: DependencyService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/**")
    fun getDispatcher(request: HttpServletRequest, model: Model): Any {
        val filename = fullFileNameFromRequest(request)

        return when {
            isInfoPage(request.parameterMap) -> info(filename)
            isDirectoryName(filename) -> list(filename, model)
            else -> fetchDependency(filename)
        }
    }

    private fun info(filename: String): ResponseEntity<FileInfo> {
        log.debug("GET info $filename")
        return dependencyService.getInfoAbout(filename)
    }

    private fun list(filename: String, model: Model): String {
        log.debug("GET listFiles $filename")
        val directory = dependencyService.getDirectory(filename)
        model.addAttribute("directory", directory)
        return "listMustache"
    }


    /*private*/ fun fetchDependency(filename: String): Any {
        log.debug("GET dependency file $filename")
        val resourceWithInfo = dependencyService.fetchDependency(filename)
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
        dependencyService.uploadDependency(fullFileName, request.inputStream)
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }
}