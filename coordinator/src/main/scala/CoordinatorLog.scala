/** Consistent stderr logging when projects are dropped or degraded during coordinator runs. */
object CoordinatorLog:
  def exclude(project: Project, reason: String, detail: String = ""): Unit =
    val suffix = if detail.nonEmpty then s" ($detail)" else ""
    Console.err.println(s"Excluding ${project.coordinates}: $reason$suffix")

  def warn(project: Project, issue: String, detail: String = ""): Unit =
    val suffix = if detail.nonEmpty then s" ($detail)" else ""
    Console.err.println(s"Warning ${project.coordinates}: $issue$suffix")
