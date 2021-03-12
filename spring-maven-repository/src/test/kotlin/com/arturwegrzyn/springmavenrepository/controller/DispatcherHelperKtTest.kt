package com.arturwegrzyn.springmavenrepository.controller

import org.junit.jupiter.api.Test

internal class DispatcherHelperKtTest {

    @Test
    fun isInfoPage() {
        // given
        val parameterMap: Map<String, Array<String>> = mapOf("test" to arrayOf("null"))

        // when
        val isInfoPage = isInfoPage(parameterMap)

        // then
        assert(isInfoPage == false)
    }

    @Test
    fun isNotInfoPage() {
        // given
        val parameterMap: Map<String, Array<String>> = emptyMap()

        // when
        val isInfoPage = isInfoPage(parameterMap)

        // then
        assert(isInfoPage == false)
    }

    @Test
    fun isDirectoryName() {
        // given
        val fullFilename = "org/scala-sbt/sbt/1.4.7"

        // when
        val isDirectoryName = isDirectoryName(fullFilename)

        // then
        assert(isDirectoryName == true)
    }

    @Test
    fun isDirectoryName_withSlash() {
        // given
        val fullFilename = "org/scala-sbt/sbt/1.4.7/"

        // when
        val isDirectoryName = isDirectoryName(fullFilename)

        // then
        assert(isDirectoryName == true)
    }

    @Test
    fun isFileName() {
        // given
        val fullFilename = "org/scala-sbt/sbt/1.4.7/sbt-1.4.7.pom"

        // when
        val isFileName = isFileName(fullFilename)

        // then
        assert(isFileName == true)
    }

    @Test
    fun isNotFileName() {
        // given
        val fullFilename = "org/scala-sbt/sbt/1.4.7"

        // when
        val isFileName = isFileName(fullFilename)

        // then
        assert(isFileName == false)
    }

    @Test
    fun isFileName_withSlash() {
        // given
        val fullFilename = "academy/alex/custommatcher/1.0/custommatcher-1.0-sources.jar/"

        // when
        val isFileName = isFileName(fullFilename)

        // then
        assert(isFileName == true)
    }

    @Test
    fun isNotFileName_withSlash() {
        // given
        val fullFilename = "academy/alex/custommatcher/1.0/"

        // when
        val isFileName = isFileName(fullFilename)

        // then
        assert(isFileName == false)
    }

    @Test
    fun isScalaDependency() {
        // given
        val fullFilename = "com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.jar"

        // when
        val isScalaDependency = isScalaDependency(fullFilename)

        // then
        assert(isScalaDependency == true)
    }

    @Test
    fun isScalaDependency_withSlash() {
        // given
        val fullFilename = "com/example/greeter_3.0.0-RC1/1.0.1/greeter_3.0.0-RC1-1.0.1.pom/"

        // when
        val isScalaDependency = isScalaDependency(fullFilename)

        // then
        assert(isScalaDependency == true)
    }

    @Test
    fun isNotScalaDependency() {
        // given
        val fullFilename = "org/scala-sbt/sbt/1.4.7/sbt-1.4.7.jar"

        // when
        val isScalaDependency = isScalaDependency(fullFilename)

        // then
        assert(isScalaDependency == false)
    }

    @Test
    fun isNotScalaDependency_withSlash() {
        // given
        val fullFilename = "org/scala-sbt/sbt/1.4.7/sbt-1.4.7.pom/"

        // when
        val isScalaDependency = isScalaDependency(fullFilename)

        // then
        assert(isScalaDependency == false)
    }
}