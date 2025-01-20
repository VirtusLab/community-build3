import java.nio.file.*
import java.nio.file.attribute.*
import java.util.regex.PatternSyntaxException

import scala.util.chaining.*
import scala.util.*
import scala.jdk.CollectionConverters.IteratorHasAsScala

given pathFromString: scala.util.CommandLineParser.FromString[Path] = Paths.get(_)

@main def searchAndReplaceAll(fileOrPattern: String, textOrPattern: String, replacement: String): Unit = 
  Try(Paths.get(fileOrPattern)) match {
    case Success(path) if Files.exists(path) && Files.isRegularFile(path) => 
      searchAndReplace(path, textOrPattern, replacement, warnIfNotApplied = true)
    case _ => 
      val pattern = fileOrPattern
      val root= Paths.get(".").toAbsolutePath().getParent()
      val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
      println(s"Using globing pattern $fileOrPattern from $root")
      Files.walkFileTree(root, new SimpleFileVisitor[Path]() {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if matcher.matches(root.relativize(file)) then
            searchAndReplace(file, textOrPattern, replacement)
          FileVisitResult.CONTINUE
        }
      })
  }
  
def searchAndReplace(file: Path, textOrPattern: String, replacement: String, warnIfNotApplied: Boolean = false): Unit =
  val input = io.Source.fromFile(file.toFile()).mkString
  input
    .replace(textOrPattern, replacement)
    .pipe: text =>
      if text != input then text
      else try textOrPattern.r.replaceAllIn(text, replacement)
      catch case _: PatternSyntaxException => text  
    .pipe: output =>
      if input != output then 
        println(s"Successfully applied pattern '$textOrPattern' in $file")
        Files.write(file, output.getBytes())
      else if warnIfNotApplied then
        System.err.println(s"Failed to apply pattern '$textOrPattern' in $file")
