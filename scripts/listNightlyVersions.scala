#!/usr/bin/env scala shebang
//> using file ./scalaVersions.scala

package scala.versions

import java.nio.file.Path
import java.nio.file.Files
import java.time.LocalDate
import scala.util.chaining.*

@main def listNightlyVersions(args: String*) = {
  var minScalaVersion = Option.empty[String]
  var maxScalaVersion = Option.empty[String]
  var nightly = false
  var refreshConfig = false
  args.foreach{
    case s"--min=$version" => minScalaVersion = Some(version)
    case s"--max=$version" => maxScalaVersion = Some(version) 
    case s"--nightly" => nightly = true
  }
  versionsRange(min = minScalaVersion, max = maxScalaVersion)(versions = Nightly)
  .foreach(println)
}

private def versionsRange(min: Option[String] = None, max: Option[String] = None)(versions: List[SemVersion]) = {
  val filters = Seq[List[SemVersion] => List[SemVersion]](
    _.sorted,
    versions => 
      min.map: minVersion =>
        versions.dropWhile(version => !version.startsWith(minVersion))
      .getOrElse(versions),
    versions => 
      max.map: maxVersion =>
        val before = versions.takeWhile(version => !version.startsWith(maxVersion))
        val after = versions.drop(before.size).takeWhile(_.startsWith(maxVersion))
        before ::: after
      .getOrElse(versions)
  )
  
  filters
  .foldLeft(versions):
    (versions, filter) => filter(versions)
}


  
