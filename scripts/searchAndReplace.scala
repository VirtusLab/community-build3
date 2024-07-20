import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.PatternSyntaxException
import java.nio.file.Files

import scala.util.chaining.*

given scala.util.CommandLineParser.FromString[Path] = Paths.get(_)

@main def searchAndReplace(file: Path, textOrPattern: String, replacement: String): Unit = 
  val input = io.Source.fromFile(file.toFile()).mkString
  input
    .replace(textOrPattern, replacement)
    .pipe: input =>
      try textOrPattern.r.replaceAllIn(input, replacement)
      catch case _: PatternSyntaxException => input  
    .pipe: output =>
      if input != output then 
        println(s"Successfully applied pattern '$textOrPattern' in $file")
        Files.write(file, output.getBytes())
      else 
        System.err.println(s"Failed to apply pattern '$textOrPattern' in $file")
