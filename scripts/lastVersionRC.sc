#!/usr/bin/env -S scala-cli shebang
//> using file ./scalaVersions.scala

println(versions.Stable.filter(_.contains("-RC")).last)
