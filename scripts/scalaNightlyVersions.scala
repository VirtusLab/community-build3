#!/usr/bin/env scala shebang

import java.nio.file.Path
import java.nio.file.Files
import java.time.LocalDate

@main def scalaNightlyVersions(args: String*) = 
  var minScalaVersion = Option.empty[String]
  var maxScalaVersion = Option.empty[String]
  var refreshConfig = false
  args.foreach{
    case s"--min=$version" => minScalaVersion = Some(version)
    case s"--max=$version" => maxScalaVersion = Some(version) 
  }
  
  val scalaVersions: List[String] = {
    Seq(
      // 3.8.0-
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/maven-metadata.xml",
      // 3.8.0+
      "https://repo.scala-lang.org/artifactory/maven-nightlies/org/scala-lang/scala3-compiler_3/maven-metadata.xml",     
    ).map(io.Source.fromURL(_).mkString)
    .flatMap: metadataXml =>
      raw"<version>(.+-bin-\d{8}-\w{7}-NIGHTLY)</version>".r
      .findAllMatchIn(metadataXml)
      .map(_.group(1))
      .filter(_ != null).toList
    .distinct.sorted
    .toList
  }
  
  val filters = Seq[List[String] => List[String]](
    _.sorted,
    versions => 
      minScalaVersion.map: minVersion =>
        versions.dropWhile(version => !version.startsWith(minVersion))
      .getOrElse(versions),
    versions => 
      maxScalaVersion.map: maxVersion =>
        val before = versions.takeWhile(version => !version.startsWith(maxVersion))
        val after = versions.drop(before.size).takeWhile(_.startsWith(maxVersion))
        before ::: after
      .getOrElse(versions)
  )
  
  val fitleredVersions = filters
  .foldLeft(scalaVersions):
    (versions, filter) => filter(versions)
  
  fitleredVersions.foreach(println)
  
  
