#!/usr/bin/env -S scala-cli shebang
val regex = raw"<version>(.+-bin-\d{8}-\w{7}-NIGHTLY)</version>".r
val xml = io.Source.fromURL(
  "https://repo.scala-lang.org/artifactory/maven-nightlies/org/scala-lang/scala3-compiler_3/maven-metadata.xml",
)
val last = regex.findAllMatchIn(xml.mkString).map(_.group(1)).filter(_ != null).toList.sorted.last
println(last)
