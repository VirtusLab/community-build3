package com.virtuslab.communitybuild.mvnrepo.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties("storage")
class StorageProperties(val location:String = "upload-dir")