package com.arturwegrzyn.springmavenrepository.storage

import org.junit.jupiter.api.Test

internal class StorageHelperKtTest {

    @Test
    fun filenameToScalaDependencyInfo_jar() {
        // given
        val fullFilename = "upload-dir/com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.jar"
        val storageLocation = "upload-dir"

        // when
        val scalaDependencyInfo = filenameToScalaDependencyInfo(fullFilename, storageLocation)

        // then
        assert(scalaDependencyInfo.organization == "com.example")
        assert(scalaDependencyInfo.name == "greeter")
        assert(scalaDependencyInfo.filename == "greeter_3.0.0-RC1-1.0.1.jar")
        assert(scalaDependencyInfo.scalaVersion == "3.0.0-RC1")
        assert(scalaDependencyInfo.version == "1.0.1")
        assert(scalaDependencyInfo.jarType == "")
        assert(scalaDependencyInfo.extension == "jar")
    }

    @Test
    fun filenameToScalaDependencyInfo_jar_withoutStorageLocation() {
        // given
        val fullFilename = "com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.jar"
        val storageLocation = "upload-dir"

        // when
        val scalaDependencyInfo = filenameToScalaDependencyInfo(fullFilename, storageLocation)

        // then
        assert(scalaDependencyInfo.organization == "com.example")
        assert(scalaDependencyInfo.name == "greeter")
        assert(scalaDependencyInfo.filename == "greeter_3.0.0-RC1-1.0.1.jar")
        assert(scalaDependencyInfo.scalaVersion == "3.0.0-RC1")
        assert(scalaDependencyInfo.version == "1.0.1")
        assert(scalaDependencyInfo.jarType == "")
        assert(scalaDependencyInfo.extension == "jar")
    }

    @Test
    fun filenameToScalaDependencyInfo_pom() {
        // given
        val fullFilename = "upload-dir/com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.pom"
        val storageLocation = "upload-dir"

        // when
        val scalaDependencyInfo = filenameToScalaDependencyInfo(fullFilename, storageLocation)

        // then
        assert(scalaDependencyInfo.organization == "com.example")
        assert(scalaDependencyInfo.name == "greeter")
        assert(scalaDependencyInfo.filename == "greeter_3.0.0-RC1-1.0.1.pom")
        assert(scalaDependencyInfo.scalaVersion == "3.0.0-RC1")
        assert(scalaDependencyInfo.version == "1.0.1")
        assert(scalaDependencyInfo.jarType == "")
        assert(scalaDependencyInfo.extension == "pom")
    }

    @Test
    fun filenameToScalaDependencyInfo_sources_jar() {
        // given
        val fullFilename = "upload-dir/com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1-sources.jar"
        val storageLocation = "upload-dir"

        // when
        val scalaDependencyInfo = filenameToScalaDependencyInfo(fullFilename, storageLocation)

        // then
        assert(scalaDependencyInfo.organization == "com.example")
        assert(scalaDependencyInfo.name == "greeter")
        assert(scalaDependencyInfo.filename == "greeter_3.0.0-RC1-1.0.1-sources.jar")
        assert(scalaDependencyInfo.scalaVersion == "3.0.0-RC1")
        assert(scalaDependencyInfo.version == "1.0.1")
        assert(scalaDependencyInfo.jarType == "sources")
        assert(scalaDependencyInfo.extension == "jar")
    }

    @Test
    fun filenameToScalaDependencyInfo_withMapping() {
        // given
        val storageLocation = "upload-dir"
        val wantedDependencyFilename = "upload-dir/com/example/greeter_3.0.0-RC1/1.0.19/greeter_3.0.0-RC1-1.0.19.jar"
        val existingDependencyInfo = filenameToScalaDependencyInfo("upload-dir/com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.jar", storageLocation)

        // when
        val scalaDependencyInfo = filenameToScalaDependencyInfo(wantedDependencyFilename, existingDependencyInfo, storageLocation)

        // then
        assert(scalaDependencyInfo.mappedTo == existingDependencyInfo)

    }
}