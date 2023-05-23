object Printer {
  val RED = """<span style="color:red">"""
  val YELLOW = """<span style="color:yellow">"""
  val MAGENTA = """<span style="color:magenta">"""
  val RESET = """</span>"""
  val BOLD = """<span style="font-weight:bold">"""
  val LINE_BREAK = "<br>"

  def println(text: String): Unit = Predef.println(s"$text")
  def log(text: String) = ()
  def printLine() = println("<hr>")

  /** make project name be a clickable link to the build */
  def projectUrlString(projectName: String, version: String, buildUrl: String): String = {
    val projectVerString = if version.isEmpty then projectName else s"$projectName @ $version"

    if buildUrl.isEmpty then projectVerString else s"[$projectVerString]($buildUrl)"
  }
}
