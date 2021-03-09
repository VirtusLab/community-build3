package com.arturwegrzyn.springmavenrepository.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties("storage")
class StorageProperties {
    /**
     * Folder location for storing files
     */
    var location = "upload-dir"
}