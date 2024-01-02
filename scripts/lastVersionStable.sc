#!/usr/bin/env -S scala-cli shebang
val regex = raw"<version>(3\.\d+\.\d+)</version>".r
val xml = io.Source.fromURL(
  "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/maven-metadata.xml"
)
val last = regex.findAllMatchIn(xml.mkString).map(_.group(1)).filter(_ != null).toList.last
println(last)
