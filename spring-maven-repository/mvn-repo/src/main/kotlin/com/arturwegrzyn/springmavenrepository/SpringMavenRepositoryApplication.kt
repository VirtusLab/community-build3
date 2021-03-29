package com.arturwegrzyn.springmavenrepository

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringMavenRepositoryApplication

fun main(args: Array<String>) {
    runApplication<SpringMavenRepositoryApplication>(*args)
}
