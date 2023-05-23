object Printer {
  import scala.Console
  val RED = Console.RED
  val YELLOW = Console.YELLOW
  val MAGENTA = Console.MAGENTA
  val RESET = Console.RESET
  val BOLD = Console.BOLD
  val LINE_BREAK = ""

  def println(text: String): Unit = Predef.println(text)
  def log(text: String) = Predef.println(text)
  def printLine() = println("-" * 20)
  def projectUrlString(projectName: String, version: String, buildUrl: String): String = {
    val projectVerString = if version.isEmpty then projectName else s"$projectName @ $version"

    if buildUrl.isEmpty then projectVerString else s"$projectVerString - $buildUrl"
  }
}
