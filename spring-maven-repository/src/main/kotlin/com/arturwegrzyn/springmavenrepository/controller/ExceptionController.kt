package com.arturwegrzyn.springmavenrepository.controller

import com.arturwegrzyn.springmavenrepository.storage.StorageException
import com.arturwegrzyn.springmavenrepository.storage.StorageFileNotFoundWithFileNameException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.view.RedirectView
import java.util.*
import java.util.stream.Collectors

@ControllerAdvice
class ExceptionController @Autowired constructor(private val env: Environment) {
    @ExceptionHandler(Exception::class)
    fun exception(e: Exception): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.message)
    }

    @ExceptionHandler(StorageFileNotFoundWithFileNameException::class)
    fun storageFileNotFoundWithFileNameException(e: StorageFileNotFoundWithFileNameException): Any {
        val mavenTargetRepositoryUrl = env.getProperty("maven.redirectUrl")
        if (Objects.nonNull(mavenTargetRepositoryUrl)) {
            val isMultiRepo = env.getProperty("maven.multiRepo")?.toBoolean() ?: false
            val filePath = if (isMultiRepo)
                e.filename.split("/").stream()
                        .skip(1)
                        .collect(Collectors.joining("/"))
            else
                e.filename

            return RedirectView("$mavenTargetRepositoryUrl/$filePath");
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.message)
        }
    }

    @ExceptionHandler(StorageException::class)
    fun storageException(e: StorageException): ResponseEntity<Any> {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.message)
    }
}