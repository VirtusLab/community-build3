package dashboard.web

import scalatags.Text.all.*
import dashboard.core.*
import dashboard.data.ProjectNote
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Reusable UI components */
object Components:

  /** URL-encode a string for use in URL paths */
  private def urlEncode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)

  /** Prefix a path with the base path */
  private def path(p: String): String =
    val base = Templates.basePath
    if p.startsWith("/") then s"$base$p" else s"$base/$p"

  /** Status badge with color coding */
  def statusBadge(status: BuildStatus): Frag =
    val (bgColor, text) = status match
      case BuildStatus.Success => ("bg-emerald-500", "Success")
      case BuildStatus.Failure => ("bg-red-500", "Failure")
      case BuildStatus.Started => ("bg-amber-500", "Running")

    span(
      cls := s"px-2 py-1 text-xs font-medium text-white rounded-full $bgColor",
      text
    )

  /** Failure reason tags */
  def failureReasons(reasons: List[FailureReason]): Frag =
    if reasons.isEmpty then frag()
    else
      div(
        cls := "flex flex-wrap gap-1 mt-1",
        reasons.map: reason =>
          val color = reason match
            case FailureReason.Compilation     => "bg-red-100 text-red-800"
            case FailureReason.TestCompilation => "bg-orange-100 text-orange-800"
            case FailureReason.Tests           => "bg-yellow-100 text-yellow-800"
            case FailureReason.Scaladoc        => "bg-purple-100 text-purple-800"
            case FailureReason.Publish         => "bg-blue-100 text-blue-800"
            case FailureReason.Build           => "bg-gray-100 text-gray-800"
            case FailureReason.Other           => "bg-gray-100 text-gray-800"
          span(
            cls := s"px-2 py-0.5 text-xs font-medium rounded $color",
            reason.toString
          )
      )

  /** Project link with optional build URL */
  def projectLink(name: ProjectName, buildURL: Option[String] = None): Frag =
    val projectUrl = path(s"/projects/${name: String}/history")
    div(
      a(
        href := projectUrl,
        cls := "text-blue-600 hover:text-blue-800 font-medium",
        name: String
      ),
      buildURL
        .filter(_.nonEmpty)
        .map: url =>
          a(
            href := url,
            target := "_blank",
            cls := "ml-2 text-gray-400 hover:text-gray-600",
            attr("title") := "View build logs",
            "↗"
          )
    )

  /** Build info card */
  def buildCard(result: BuildResult): Frag =
    val logsUrl = path(
      s"/projects/${result.projectName.org}/${result.projectName.repo}/builds/${urlEncode(result.buildId)}/logs"
    )

    div(
      cls := "bg-white rounded-lg shadow p-4 border-l-4",
      cls := (if result.status == BuildStatus.Success then "border-emerald-500" else "border-red-500"),
      div(
        cls := "flex justify-between items-start",
        div(
          projectLink(result.projectName, Some(result.buildURL)),
          p(cls := "text-sm text-gray-500 mt-1", s"Version: ${result.version}")
        ),
        statusBadge(result.status)
      ),
      failureReasons(result.failureReasons),
      div(
        cls := "mt-2 text-xs text-gray-400 flex items-center gap-3",
        span(s"${result.buildTool} • ${result.scalaVersion}"),
        a(href := logsUrl, cls := "text-blue-600 hover:underline font-medium", "📋 Logs")
      )
    )

  /** Build info card with failure duration badge */
  def buildCardWithDuration(result: BuildResult, streakInfo: Option[FailureStreakInfo]): Frag =
    val logsUrl = path(
      s"/projects/${result.projectName.org}/${result.projectName.repo}/builds/${urlEncode(result.buildId)}/logs"
    )

    div(
      cls := "bg-white rounded-lg shadow p-4 border-l-4",
      cls := (if result.status == BuildStatus.Success then "border-emerald-500" else "border-red-500"),
      div(
        cls := "flex justify-between items-start",
        div(
          projectLink(result.projectName, Some(result.buildURL)),
          p(cls := "text-sm text-gray-500 mt-1", s"Version: ${result.version}")
        ),
        div(
          cls := "flex items-center gap-2",
          // Duration badge for failures - shows days and starting Scala version
          streakInfo
            .filter(_ => result.status == BuildStatus.Failure)
            .map: info =>
              val (bgColor, textColor) = info.days match
                case d if d >= 30 => ("bg-red-100", "text-red-700")
                case d if d >= 7  => ("bg-orange-100", "text-orange-700")
                case _            => ("bg-yellow-100", "text-yellow-700")
              val daysText =
                if info.days == 0 then "< 1 day"
                else if info.days == 1 then "1 day"
                else s"${info.days} days"
              span(
                cls := s"px-2 py-1 text-xs font-medium rounded $bgColor $textColor whitespace-nowrap",
                title := s"Failing for ${info.days} days, started on ${info.startedOnScalaVersion}",
                s"$daysText • ${info.startedOnScalaVersion}"
              )
          ,
          statusBadge(result.status)
        )
      ),
      failureReasons(result.failureReasons),
      div(
        cls := "mt-2 text-xs text-gray-400 flex items-center gap-3",
        span(s"${result.buildTool} • ${result.scalaVersion}"),
        a(href := logsUrl, cls := "text-blue-600 hover:underline font-medium", "📋 Logs")
      )
    )

  /** Comparison diff row */
  def diffRow(diff: ProjectDiff, diffType: String): Frag =
    val (icon, rowClass) = diffType match
      case "new-failure"   => ("❌", "bg-red-50")
      case "new-fix"       => ("✅", "bg-green-50")
      case "still-failing" => ("⚠️", "bg-yellow-50")
      case _               => ("", "")

    val logsUrl = path(
      s"/projects/${diff.projectName.org}/${diff.projectName.repo}/builds/${urlEncode(diff.buildId)}/logs"
    )

    tr(
      cls := rowClass,
      td(cls := "px-4 py-3 whitespace-nowrap", icon),
      td(
        cls := "px-4 py-3",
        projectLink(diff.projectName, Some(diff.buildURL))
      ),
      td(cls := "px-4 py-3 text-sm text-gray-600", diff.version),
      td(cls := "px-4 py-3", statusBadge(diff.targetStatus)),
      td(cls := "px-4 py-3", failureReasons(diff.failureReasons)),
      td(cls := "px-4 py-3", a(href := logsUrl, cls := "text-blue-600 hover:underline text-sm font-medium", "📋 Logs"))
    )

  /** History timeline entry */
  def historyEntry(entry: ProjectHistoryEntry): Frag =
    val statusColor = entry.status match
      case BuildStatus.Success => "bg-emerald-500"
      case BuildStatus.Failure => "bg-red-500"
      case BuildStatus.Started => "bg-amber-500"

    // Build URL for local logs (from Elasticsearch)
    val logsUrl = path(
      s"/projects/${entry.projectName.org}/${entry.projectName.repo}/builds/${urlEncode(entry.buildId)}/logs"
    )

    div(
      cls := "flex items-start gap-4 pb-4",
      // Timeline dot
      div(
        cls := "relative",
        div(cls := s"w-3 h-3 rounded-full $statusColor mt-1.5"),
        div(cls := "absolute top-4 left-1.5 w-px h-full bg-gray-200")
      ),
      // Content
      div(
        cls := "flex-1 pb-4 border-b border-gray-100 last:border-0",
        div(
          cls := "flex justify-between items-start",
          div(
            span(cls := "font-medium text-gray-900", entry.scalaVersion),
            span(cls := "ml-2 text-sm text-gray-500", s"v${entry.version}")
          ),
          statusBadge(entry.status)
        ),
        failureReasons(entry.failureReasons),
        div(
          cls := "mt-1 text-xs text-gray-400 flex items-center gap-3",
          span(formatTimestampShort(entry.timestamp)),
          // Link to local logs (from Elasticsearch - persistent)
          a(href := logsUrl, cls := "text-blue-600 hover:underline font-medium", "📋 Logs"),
          // Link to GitHub Actions (ephemeral - 60 days)
          entry.buildURL match
            case url if url.nonEmpty =>
              a(href := url, target := "_blank", cls := "text-gray-500 hover:underline", "GitHub Actions ↗")
            case _ => frag()
        )
      )
    )

  /** Format timestamp for display in history entries */
  private def formatTimestampShort(instant: java.time.Instant): String =
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("MMM d, yyyy HH:mm")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)

  /** Log entry with severity coloring */
  def logEntry(entry: LogEntry): Frag =
    val (bgColor, textColor, lineNumColor) = entry.severity match
      // Foggy/muted red - delicate, not bright
      case LogSeverity.Error => ("bg-rose-100", "text-rose-900 font-medium", "text-rose-400")
      // Light orange/amber - warm and visible
      case LogSeverity.Warning => ("bg-amber-100", "text-amber-900 font-medium", "text-amber-500")
      // Light gray background with black text
      case LogSeverity.Info => ("bg-gray-100", "text-gray-900", "text-gray-400")

    div(
      cls := s"font-mono text-sm py-1 px-3 $bgColor $textColor border-b border-gray-200",
      span(cls := s"$lineNumColor mr-4 select-none", f"${entry.line}%5d"),
      entry.content
    )

  /** Note card */
  def noteCard(note: ProjectNote): Frag =
    div(
      cls := "bg-blue-50 rounded-lg p-4 mb-3",
      div(
        cls := "flex justify-between items-start",
        div(
          span(cls := "font-medium text-blue-900", s"@${note.githubUser}"),
          span(cls := "ml-2 text-xs text-blue-600", note.createdAt.toString)
        ),
        note.githubIssueUrl.map: url =>
          a(
            href := url,
            target := "_blank",
            cls := "text-blue-600 hover:text-blue-800 text-sm",
            "GitHub Issue ↗"
          )
      ),
      p(cls := "mt-2 text-gray-700", note.note),
      note.buildId.map(bid => p(cls := "mt-1 text-xs text-gray-500", s"Build: $bid"))
    )

  /** Note form for adding new notes */
  def noteForm(projectName: String): Frag =
    div(
      id := "note-form",
      cls := "bg-yellow-50 border border-yellow-200 rounded-lg p-4 mb-3",
      form(
        attr("hx-post") := path(s"/projects/$projectName/notes"),
        attr("hx-target") := "#note-form",
        attr("hx-swap") := "outerHTML",
        div(
          cls := "mb-3",
          tag("label")(
            cls := "block text-sm font-medium text-gray-700 mb-1",
            "Note"
          ),
          tag("textarea")(
            name := "note",
            cls := "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
            attr("rows") := "3",
            attr("placeholder") := "Enter your note about this project...",
            attr("required") := "required"
          )
        ),
        div(
          cls := "mb-3",
          tag("label")(
            cls := "block text-sm font-medium text-gray-700 mb-1",
            "GitHub Issue URL (optional)"
          ),
          input(
            tpe := "url",
            name := "githubIssueUrl",
            cls := "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
            attr("placeholder") := "https://github.com/..."
          )
        ),
        div(
          cls := "flex justify-end gap-2",
          button(
            tpe := "button",
            cls := "px-4 py-2 text-sm text-gray-600 hover:text-gray-800",
            attr("onclick") := "this.closest('#note-form').remove()",
            "Cancel"
          ),
          button(
            tpe := "submit",
            cls := "px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 transition-colors",
            "Save Note"
          )
        )
      )
    )

  /** Empty state message */
  def emptyState(message: String): Frag =
    div(
      cls := "text-center py-12",
      div(cls := "text-gray-400 text-4xl mb-4", "📭"),
      p(cls := "text-gray-500", message)
    )

  /** Loading spinner - large, centered */
  def loadingSpinner: Frag =
    div(cls := "animate-spin rounded-full h-5 w-5 border-2 border-blue-600 border-t-transparent")

  /** Loading spinner - inline, small */
  def loadingSpinnerSmall: Frag =
    div(cls := "animate-spin rounded-full h-4 w-4 border-2 border-blue-600 border-t-transparent")

  /** Loading placeholder - full area */
  def loadingPlaceholder: Frag =
    div(
      cls := "flex justify-center items-center py-12",
      div(cls := "animate-spin rounded-full h-8 w-8 border-2 border-blue-600 border-t-transparent"),
      span(cls := "ml-3 text-gray-500", "Loading...")
    )

  /** Filter buttons for htmx - uses hx-include to pass form values */
  def filterButtons(currentFilter: String, currentReason: Option[String]): Frag =
    div(
      cls := "flex flex-wrap gap-2 mb-4 items-center",
      // Type filters
      div(
        cls := "flex gap-2",
        List(
          "all" -> "All",
          "new-failures" -> "New Failures",
          "new-fixes" -> "New Fixes",
          "still-failing" -> "Still Failing"
        ).map: (value, label) =>
          val active = currentFilter == value
          button(
            cls := s"px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
                if active then "bg-blue-600 text-white" else "bg-gray-100 text-gray-700 hover:bg-gray-200"
              }",
            attr("hx-get") := path("/compare/filter"),
            attr("hx-target") := "#results",
            attr("hx-swap") := "innerHTML",
            attr("hx-include") := "#comparison-params",
            attr("hx-indicator") := "#compare-loading",
            attr("hx-vals") := s"""{"filter": "$value"}""",
            label
          )
      ),
      // Reason filters
      div(
        cls := "flex gap-2 ml-4 border-l pl-4 items-center",
        span(cls := "text-sm text-gray-500 self-center mr-2", "Reason:"),
        List(
          None -> "Any",
          Some("Compilation") -> "Compile",
          Some("TestCompilation") -> "Test Compile",
          Some("Tests") -> "Tests",
          Some("Scaladoc") -> "Scaladoc",
          Some("Publish") -> "Publish",
          Some("Build") -> "Build"
        ).map: (reasonOpt, label) =>
          val active = currentReason == reasonOpt
          val colorClass = reasonOpt match
            case Some("Compilation") | Some("TestCompilation") =>
              if active then "bg-red-600 text-white" else "bg-red-100 text-red-700 hover:bg-red-200"
            case Some("Tests") =>
              if active then "bg-orange-600 text-white" else "bg-orange-100 text-orange-700 hover:bg-orange-200"
            case Some("Scaladoc") =>
              if active then "bg-purple-600 text-white" else "bg-purple-100 text-purple-700 hover:bg-purple-200"
            case Some("Publish") =>
              if active then "bg-yellow-600 text-white" else "bg-yellow-100 text-yellow-700 hover:bg-yellow-200"
            case Some("Build") =>
              if active then "bg-gray-600 text-white" else "bg-gray-200 text-gray-700 hover:bg-gray-300"
            case None =>
              if active then "bg-purple-600 text-white" else "bg-purple-100 text-purple-700 hover:bg-purple-200"
            case _ =>
              if active then "bg-purple-600 text-white" else "bg-purple-100 text-purple-700 hover:bg-purple-200"
          button(
            cls := s"px-3 py-1 rounded text-xs font-medium transition-colors $colorClass",
            attr("hx-get") := path("/compare/filter"),
            attr("hx-target") := "#results",
            attr("hx-swap") := "innerHTML",
            attr("hx-include") := "#comparison-params",
            attr("hx-indicator") := "#compare-loading",
            attr("hx-vals") := reasonOpt.map(r => s"""{"reason": "$r"}""").getOrElse("""{"reason": ""}"""),
            label
          )
      ),
      // Loading indicator
      div(
        id := "compare-loading",
        cls := "htmx-indicator ml-4",
        loadingSpinnerSmall,
        span(cls := "ml-2 text-sm text-gray-500", "Loading...")
      )
    )

  /** Severity filter for logs */
  def severityFilter(currentSeverity: String, targetUrl: String): Frag =
    div(
      cls := "flex gap-2 mb-4",
      List("all" -> "All Logs", "warning" -> "Warnings & Errors", "error" -> "Errors Only").map: (value, label) =>
        val active = currentSeverity == value
        val colorClass = value match
          case "error"   => if active then "bg-red-600 text-white" else "bg-red-100 text-red-700 hover:bg-red-200"
          case "warning" =>
            if active then "bg-yellow-500 text-white" else "bg-yellow-100 text-yellow-700 hover:bg-yellow-200"
          case _ => if active then "bg-gray-600 text-white" else "bg-gray-100 text-gray-700 hover:bg-gray-200"
        button(
          cls := s"px-4 py-2 rounded-lg text-sm font-medium transition-colors $colorClass",
          attr("hx-get") := s"$targetUrl?severity=$value",
          attr("hx-target") := "#log-content",
          attr("hx-swap") := "innerHTML",
          label
        )
    )
