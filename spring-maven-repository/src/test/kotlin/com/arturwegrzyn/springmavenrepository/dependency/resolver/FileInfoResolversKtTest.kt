package com.arturwegrzyn.springmavenrepository.dependency.resolver

import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.ScalaDependencyInfo
import org.junit.jupiter.api.Test

internal class FileInfoResolversKtTest {

    private fun getFileInfoResolverChain(): FileInfoResolver {
        return ScalaDependencyInfoResolver(JavaDependencyInfoResolver(DirectoryInfoResolver(null)))
    }

    @Test
    fun filenameToScalaDependencyInfo_jar() {
        // given
        val resolvers = getFileInfoResolverChain()
        val fullFilename = "com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.jar"

        // when
        val dependencyInfo = resolvers.resolve(fullFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
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
        val resolvers = getFileInfoResolverChain()
        val fullFilename = "com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.pom"

        // when
        val dependencyInfo = resolvers.resolve(fullFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
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
        val resolvers = getFileInfoResolverChain()
        val fullFilename = "com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1-sources.jar"

        // when
        val dependencyInfo = resolvers.resolve(fullFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
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
        val resolvers = getFileInfoResolverChain()
        val wantedDependencyFilename = "com/example/greeter_3.0.0-RC1/1.0.19/greeter_3.0.0-RC1-1.0.19.jar"
        val existingDependencyInfo = resolvers.resolve("com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.jar")

        // when
        val scalaDependencyInfo = resolvers.resolve(wantedDependencyFilename, existingDependencyInfo)

        // then
        assert(scalaDependencyInfo.mappedTo == existingDependencyInfo)

    }

    @Test
    fun filenameToScalaDependencyInfo_md5HashFile(){
        // given
        val resolvers = getFileInfoResolverChain()
        val wantedDependencyFilename = "com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1-sources.jar.md5"

        // when
        val dependencyInfo = resolvers.resolve(wantedDependencyFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
        assert(scalaDependencyInfo.organization == "com.example")
        assert(scalaDependencyInfo.name == "greeter")
        assert(scalaDependencyInfo.filename == "greeter_3.0.0-RC1-1.0.1-sources.jar.md5")
        assert(scalaDependencyInfo.scalaVersion == "3.0.0-RC1")
        assert(scalaDependencyInfo.version == "1.0.1")
        assert(scalaDependencyInfo.jarType == "sources")
        assert(scalaDependencyInfo.extension == "jar")

    }
}