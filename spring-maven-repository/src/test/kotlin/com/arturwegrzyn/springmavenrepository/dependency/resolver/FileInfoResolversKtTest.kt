package com.arturwegrzyn.springmavenrepository.dependency.resolver

import com.arturwegrzyn.springmavenrepository.dependency.DependencyService
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.JavaDependencyInfo
import com.arturwegrzyn.springmavenrepository.dependency.resolver.model.ScalaDependencyInfo
import org.junit.jupiter.api.Test

internal class FileInfoResolversKtTest {

    private fun getFileInfoResolverChain(): FileInfoResolver {
        return DependencyService.resolvers;
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
        assert(scalaDependencyInfo.type == "")
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
        assert(scalaDependencyInfo.type == "")
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
        assert(scalaDependencyInfo.type == "sources")
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
        assert(scalaDependencyInfo.type == "sources.jar")
        assert(scalaDependencyInfo.extension == "md5")

    }

    @Test
    fun filenameToScalaDependencyInfo_cornerCase1_numbersInOrganizationName(){
        // given
        val resolvers = getFileInfoResolverChain()
        val wantedDependencyFilename = "com/eed3si9n/sjson-new-murmurhash_2.12/0.9.1/sjson-new-murmurhash_2.12-0.9.1.jar"

        // when
        val dependencyInfo = resolvers.resolve(wantedDependencyFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
        assert(scalaDependencyInfo.organization == "com.eed3si9n")
        assert(scalaDependencyInfo.name == "sjson-new-murmurhash")
        assert(scalaDependencyInfo.filename == "sjson-new-murmurhash_2.12-0.9.1.jar")
        assert(scalaDependencyInfo.scalaVersion == "2.12")
        assert(scalaDependencyInfo.version == "0.9.1")
        assert(scalaDependencyInfo.type == "")
        assert(scalaDependencyInfo.extension == "jar")

    }

    @Test
    fun filenameToScalaDependencyInfo_cornerCase2(){
        // given
        val resolvers = getFileInfoResolverChain()
        val wantedDependencyFilename = "com/eed3si9n/sjson-new-scalajson_2.12/0.9.1/sjson-new-scalajson_2.12-0.9.1.pom"

        // when
        val dependencyInfo = resolvers.resolve(wantedDependencyFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
        assert(scalaDependencyInfo.organization == "com.eed3si9n")
        assert(scalaDependencyInfo.name == "sjson-new-scalajson")
        assert(scalaDependencyInfo.filename == "sjson-new-scalajson_2.12-0.9.1.pom")
        assert(scalaDependencyInfo.scalaVersion == "2.12")
        assert(scalaDependencyInfo.version == "0.9.1")
        assert(scalaDependencyInfo.type == "")
        assert(scalaDependencyInfo.extension == "pom")

    }

    @Test
    fun filenameToScalaDependencyInfo_cornerCase3(){
        // given
        val resolvers = getFileInfoResolverChain()
        val wantedDependencyFilename = "com/eed3si9n/shaded-scalajson_2.12/1.0.0-M4/shaded-scalajson_2.12-1.0.0-M4.pom"

        // when
        val dependencyInfo = resolvers.resolve(wantedDependencyFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
        assert(scalaDependencyInfo.organization == "com.eed3si9n")
        assert(scalaDependencyInfo.name == "shaded-scalajson")
        assert(scalaDependencyInfo.filename == "shaded-scalajson_2.12-1.0.0-M4.pom")
        assert(scalaDependencyInfo.scalaVersion == "2.12")
        assert(scalaDependencyInfo.version == "1.0.0-M4")
        assert(scalaDependencyInfo.type == "")
        assert(scalaDependencyInfo.extension == "pom")

    }

    @Test
    fun filenameToJavaDependencyInfo_cornerCase4(){
        // given
        val resolvers = getFileInfoResolverChain()
        val wantedDependencyFilename = "org/scala-sbt/jline/jline/2.14.7-sbt-5e51b9d4f9631ebfa29753ce4accc57808e7fd6b/jline-2.14.7-sbt-5e51b9d4f9631ebfa29753ce4accc57808e7fd6b.pom"

        // when
        val dependencyInfo = resolvers.resolve(wantedDependencyFilename)

        // then
        assert(dependencyInfo is JavaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as JavaDependencyInfo
        assert(scalaDependencyInfo.organization == "org.scala-sbt.jline")
        assert(scalaDependencyInfo.name == "jline")
        assert(scalaDependencyInfo.filename == "jline-2.14.7-sbt-5e51b9d4f9631ebfa29753ce4accc57808e7fd6b.pom")
        assert(scalaDependencyInfo.version == "2.14.7-sbt-5e51b9d4f9631ebfa29753ce4accc57808e7fd6b")
        assert(scalaDependencyInfo.type == "")
        assert(scalaDependencyInfo.extension == "pom")

    }

    @Test
    fun filenameToScalaDependencyInfo_cornerCase5(){
        // given
        val resolvers = getFileInfoResolverChain()
        val wantedDependencyFilename = "ch/epfl/scala/sbt-bloop_2.12_1.0/1.4.8/sbt-bloop-1.4.8.jar"

        // when
        val dependencyInfo = resolvers.resolve(wantedDependencyFilename)

        // then
        assert(dependencyInfo is ScalaDependencyInfo)
        val scalaDependencyInfo = dependencyInfo as ScalaDependencyInfo
        assert(scalaDependencyInfo.organization == "ch.epfl.scala")
        assert(scalaDependencyInfo.name == "sbt-bloop")
        assert(scalaDependencyInfo.filename == "sbt-bloop-1.4.8.jar")
        assert(scalaDependencyInfo.scalaVersion == "2.12")
        assert(scalaDependencyInfo.version == "1.4.8")
        assert(scalaDependencyInfo.type == "")
        assert(scalaDependencyInfo.extension == "jar")

    }
}