#!/usr/bin/env -S scala-cli shebang
val regex = raw"""(?<=title=")(3\.\d+\.\d+)(?=/")""".r
val html = io.Source.fromURL("https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/")
val last = regex.findAllIn(html.mkString).toList.last
println(last)