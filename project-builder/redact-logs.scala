import scala.io.Source
import java.nio.file.{Files, Paths}

@main def redactLogs(
    inputFile: String,
    outputFile: String,
    secrets: String*
): Unit = {
  // Read the log file
  val content = Source.fromFile(inputFile).mkString

  // Remove ANSI color codes
  val contentWithoutColors = content.replaceAll("\u001B\\[[0-9;]*[mGK]", "")

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