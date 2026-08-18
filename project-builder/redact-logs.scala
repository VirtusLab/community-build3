import scala.io.Source
import java.nio.file.{Files, Paths}

@main def redactLogs(
    inputFile: String,
    outputFile: String,
    secrets: String*
): Unit = {
  // Read the log file
  val content = Source.fromFile(inputFile).mkString

  // Remove ANSI escape sequences: CSI (colors, cursor moves, erase), OSC and 2-char escapes
  val AnsiEscapePattern =
    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?|[@-Z\\\\-_])"
  val contentWithoutColors = content.replaceAll(AnsiEscapePattern, "")

  // Redact secrets using foldLeft
  val redactedContent = secrets
    .filter(_.nonEmpty)
    .foldLeft(contentWithoutColors) { (acc, secret) =>
      acc.replace(secret, "<REDACTED>")
    }

  // Write the redacted logs
  Files.write(Paths.get(outputFile), redactedContent.getBytes)
  println(s"Redacted logs written to $outputFile")
}