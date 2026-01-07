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

  /** Build info card with failure duration badge and notes */
  def buildCardWithDuration(
      result: BuildResult,
      streakInfo: Option[FailureStreakInfo],
      notes: List[ProjectNote] = Nil,
      canEdit: Boolean = false
  ): Frag =
    val logsUrl = path(
      s"/projects/${result.projectName.org}/${result.projectName.repo}/builds/${urlEncode(result.buildId)}/logs"
    )
    val notesCellId = s"home-notes-${result.projectName.org}-${result.projectName.repo}-${result.buildId.hashCode.abs}"

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
        a(href := logsUrl, cls := "text-blue-600 hover:underline font-medium", "📋 Logs"),
        // Notes indicator
        span(
          cls := "ml-auto",
          noteIndicator(result.projectName: String, result.buildId, notesCellId, notes, canEdit)
        )
      )
    )

  /** Comparison diff row with pre-fetched notes (avoids N+1 HTTP requests) */
  def diffRow(diff: ProjectDiff, diffType: String, notes: List[ProjectNote] = Nil, isLoggedIn: Boolean = false): Frag =
    val (icon, rowClass) = diffType match
      case "new-failure"   => ("❌", "bg-red-50")
      case "new-fix"       => ("✅", "bg-green-50")
      case "still-failing" => ("⚠️", "bg-yellow-50")
      case _               => ("", "")

    val logsUrl = path(
      s"/projects/${diff.projectName.org}/${diff.projectName.repo}/builds/${urlEncode(diff.buildId)}/logs"
    )
    val noteIndicatorId = s"note-ind-${diff.projectName.org}-${diff.projectName.repo}-${diff.buildId.hashCode.abs}"

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
      td(cls := "px-4 py-3", a(href := logsUrl, cls := "text-blue-600 hover:underline text-sm font-medium", "📋 Logs")),
      // Notes column - rendered inline with pre-fetched notes (no extra HTTP request)
      td(
        cls := "px-4 py-3 text-sm",
        span(
          id := noteIndicatorId,
          noteIndicatorContent(diff.projectName: String, diff.buildId, noteIndicatorId, notes, isLoggedIn)
        )
      )
    )

  /** History timeline entry with pre-fetched notes (avoids N+1 requests) */
  def historyEntry(
      entry: ProjectHistoryEntry,
      notes: List[ProjectNote] = Nil,
      canEdit: Boolean = false
  ): Frag =
    val statusColor = entry.status match
      case BuildStatus.Success => "bg-emerald-500"
      case BuildStatus.Failure => "bg-red-500"
      case BuildStatus.Started => "bg-amber-500"

    // Build URL for local logs (from Elasticsearch)
    val logsUrl = path(
      s"/projects/${entry.projectName.org}/${entry.projectName.repo}/builds/${urlEncode(entry.buildId)}/logs"
    )
    val notesCellId = s"history-notes-${entry.projectName.org}-${entry.projectName.repo}-${entry.buildId.hashCode.abs}"

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
        ),
        // Build-level notes - rendered inline with pre-fetched data
        notesCellContent(entry.projectName: String, entry.buildId, notesCellId, notes, canEdit)
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

  /** Project-level note card with delete button (for users with edit access) */
  def projectNoteCard(note: ProjectNote, canEdit: Boolean): Frag =
    val noteCardId = s"project-note-${note.id}"
    div(
      id := noteCardId,
      cls := "bg-blue-50 rounded-lg p-4 mb-3",
      div(
        cls := "flex justify-between items-start",
        div(
          span(cls := "font-medium text-blue-900", s"@${note.githubUser}"),
          span(cls := "ml-2 text-xs text-blue-600", note.createdAt.toString)
        ),
        div(
          cls := "flex items-center gap-2",
          note.githubIssueUrl.map: url =>
            a(
              href := url,
              target := "_blank",
              cls := "text-blue-600 hover:text-blue-800 text-sm",
              "GitHub Issue ↗"
            ),
          // Delete button - only for users with edit access
          if canEdit then
            button(
              tpe := "button",
              cls := "text-red-400 hover:text-red-600 text-sm ml-2",
              title := "Delete note",
              attr("hx-delete") := path(s"/projects/${note.projectName: String}/notes/${note.id}/delete"),
              attr("hx-target") := s"#$noteCardId",
              attr("hx-swap") := "outerHTML",
              attr("hx-confirm") := "Delete this project note?",
              "✕"
            )
          else frag()
        )
      ),
      p(cls := "mt-2 text-gray-700", note.note)
    )

  /** Note form for adding new notes */
  def noteForm(projectName: String): Frag =
    noteFormWithBuild(projectName, None, "note-form")

  /** Note form for adding build-specific notes (inline in comparison table) */
  def noteFormInline(projectName: String, buildId: String, formId: String): Frag =
    noteFormWithBuild(projectName, Some(buildId), formId)

  /** Note form with optional build ID */
  private def noteFormWithBuild(projectName: String, buildId: Option[String], formId: String): Frag =
    val isInline = buildId.isDefined
    if isInline then
      // Inline form - absolute positioned dropdown
      div(
        cls := "absolute left-0 top-full mt-1 z-50 bg-white border border-blue-200 rounded-lg p-3 shadow-lg",
        style := "min-width: 280px;",
        form(
          attr("hx-post") := path(s"/projects/$projectName/notes"),
          attr("hx-target") := s"#$formId",
          attr("hx-swap") := "innerHTML",
          // Hidden fields
          input(tpe := "hidden", name := "buildId", value := buildId.get),
          input(tpe := "hidden", name := "formId", value := formId),
          div(
            cls := "mb-2",
            tag("textarea")(
              name := "note",
              cls := "w-full border border-gray-300 rounded px-2 py-1 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
              attr("rows") := "2",
              attr("placeholder") := "Note for this build...",
              attr("required") := "required"
            )
          ),
          div(
            cls := "mb-2",
            input(
              tpe := "url",
              name := "githubIssueUrl",
              cls := "w-full border border-gray-300 rounded px-2 py-1 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
              attr("placeholder") := "GitHub Issue URL (optional)"
            )
          ),
          div(
            cls := "flex justify-end gap-2",
            button(
              tpe := "button",
              cls := "px-3 py-1 text-xs text-gray-600 hover:text-gray-800",
              attr("hx-get") := path(s"/projects/$projectName/notes/cancel?formId=$formId&buildId=${urlEncode(buildId.get)}"),
              attr("hx-target") := s"#$formId",
              attr("hx-swap") := "innerHTML",
              "Cancel"
            ),
            button(
              tpe := "submit",
              cls := "px-3 py-1 bg-blue-600 text-white text-xs rounded hover:bg-blue-700 transition-colors",
              "Save"
            )
          )
        )
      )
    else
      // Full page form
      div(
        id := formId,
        cls := "bg-yellow-50 border border-yellow-200 rounded-lg p-4 mb-3",
        form(
          attr("hx-post") := path(s"/projects/$projectName/notes"),
          attr("hx-target") := s"#$formId",
          attr("hx-swap") := "outerHTML",
          div(
            cls := "mb-3",
            tag("textarea")(
              name := "note",
              cls := "w-full border border-gray-300 rounded px-2 py-1 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
              attr("rows") := "3",
              attr("placeholder") := "Enter your note about this project...",
              attr("required") := "required"
            )
          ),
          div(
            cls := "mb-3",
            input(
              tpe := "url",
              name := "githubIssueUrl",
              cls := "w-full border border-gray-300 rounded px-2 py-1 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
              attr("placeholder") := "GitHub Issue URL (optional)"
            )
          ),
          div(
            cls := "flex justify-end gap-2",
            button(
              tpe := "button",
              cls := "px-3 py-1 text-xs text-gray-600 hover:text-gray-800",
              attr("onclick") := s"document.getElementById('$formId').remove()",
              "Cancel"
            ),
            button(
              tpe := "submit",
              cls := "px-3 py-1 bg-blue-600 text-white text-xs rounded hover:bg-blue-700 transition-colors",
              "Save"
            )
          )
        )
      )

  /** Inline note success - reloads the indicator content */
  def noteAddedInline(projectName: String, buildId: String, formId: String): Frag =
    // Return content that triggers reload of the indicator
    frag(
      span(cls := "text-green-600 text-xs", "✓ Saved"),
      // This div will trigger reload of the indicator
      div(
        attr("hx-get") := path(s"/projects/$projectName/notes/indicator?buildId=${urlEncode(buildId)}&indicatorId=$formId"),
        attr("hx-trigger") := "load",
        attr("hx-target") := s"#$formId",
        attr("hx-swap") := "innerHTML"
      )
    )

  /** Cancel - returns to indicator content */
  def noteFormCancelled(projectName: String, buildId: Option[String], formId: String): Frag =
    buildId match
      case Some(bid) =>
        // For inline forms, reload the indicator content
        frag(
          span(cls := "text-gray-300", "..."),
          div(
            attr("hx-get") := path(s"/projects/$projectName/notes/indicator?buildId=${urlEncode(bid)}&indicatorId=$formId"),
            attr("hx-trigger") := "load",
            attr("hx-target") := s"#$formId",
            attr("hx-swap") := "innerHTML"
          )
        )
      case None =>
        // For full page forms - just remove
        frag()

  /** Notes cell content for comparison table - shows notes inline with add button */
  def notesCellContent(
      projectName: String,
      buildId: String,
      cellId: String,
      notes: List[ProjectNote],
      canEdit: Boolean
  ): Frag =
    def addNoteButton(hasNotes: Boolean): Frag =
      if !canEdit then frag()
      else
        val (btnClass, label) =
          if hasNotes then ("text-blue-600 hover:text-blue-800 mt-1", "➕ Add")
          else ("text-gray-400 hover:text-blue-600 transition-colors", "📝 Add note")
        button(
          tpe := "button",
          cls := btnClass,
          attr("hx-get") := path(s"/projects/$projectName/notes/form?buildId=${urlEncode(buildId)}&formId=$cellId"),
          attr("hx-target") := s"#$cellId",
          attr("hx-swap") := "innerHTML",
          label
        )

    def noteCard(note: ProjectNote): Frag =
      div(
        cls := "bg-blue-50 rounded p-1.5 mb-1.5",
        div(
          cls := "flex justify-between items-start gap-1",
          span(cls := "font-medium text-blue-800", s"@${note.githubUser} at ${formatTimestampShort(note.createdAt)}"),
          div(
            cls := "flex items-center gap-1",
            note.githubIssueUrl.map: url =>
              a(href := url, target := "_blank", cls := "text-blue-600 hover:text-blue-800", "↗"),
            if canEdit then
              button(
                tpe := "button",
                cls := "text-red-400 hover:text-red-600 ml-1",
                title := "Delete note",
                attr("hx-delete") := path(s"/projects/$projectName/notes/${note.id}?cellId=$cellId&buildId=${urlEncode(buildId)}"),
                attr("hx-target") := s"#$cellId",
                attr("hx-swap") := "outerHTML",
                attr("hx-confirm") := "Delete this note?",
                "✕"
              )
            else frag()
          )
        ),
        p(cls := "text-gray-700 mt-0.5 whitespace-pre-wrap break-words", note.note)
      )

    div(
      id := cellId,
      cls := "text-xs",
      notes.map(noteCard),
      addNoteButton(hasNotes = notes.nonEmpty)
    )

  /** Note indicator content - renders content that goes inside a span with id (for inline rendering) */
  def noteIndicatorContent(
      projectName: String,
      buildId: String,
      indicatorId: String,
      notes: List[ProjectNote],
      isLoggedIn: Boolean
  ): Frag =
    if notes.isEmpty && !isLoggedIn then
      // No notes and cannot edit - leave blank
      frag()
    else if notes.isEmpty && isLoggedIn then
      // No notes but logged in - show add button
      button(
        tpe := "button",
        cls := "text-gray-300 hover:text-blue-600 text-sm opacity-50 hover:opacity-100 transition-opacity",
        attr("hx-get") := path(s"/projects/$projectName/notes/form?buildId=${urlEncode(buildId)}&formId=$indicatorId"),
        attr("hx-target") := s"#$indicatorId",
        attr("hx-swap") := "innerHTML",
        title := "Add note",
        "📝"
      )
    else
      // Has notes - show indicator with tooltip
      // Helper for tooltip content
      val tooltipContent = div(
        cls := "absolute right-full top-0 mr-2 z-50 hidden group-hover:block pointer-events-none",
        div(
          cls := "bg-gray-900 text-white text-xs rounded-lg py-2 px-3 shadow-lg whitespace-pre-wrap text-left",
          style := "min-width: 300px; max-width: 500px; width: max-content;",
          notes.map: note =>
            div(
              cls := "mb-2 last:mb-0",
              div(cls := "text-gray-400 text-[10px]", s"@${note.githubUser} at ${formatTimestampShort(note.createdAt)}"),
              div(note.note)
            )
        )
      )

      if isLoggedIn then
        button(
          tpe := "button",
          cls := "text-blue-500 hover:text-blue-700 text-sm relative group inline-flex items-center",
          attr("hx-get") := path(s"/projects/$projectName/notes/form?buildId=${urlEncode(buildId)}&formId=$indicatorId"),
          attr("hx-target") := s"#$indicatorId",
          attr("hx-swap") := "innerHTML",
          title := "View/add notes",
          s"📝${notes.length}",
          tooltipContent
        )
      else
        span(
          cls := "text-blue-500 text-sm cursor-default relative group inline-flex items-center",
          s"📝${notes.length}",
          tooltipContent
        )

  /** Note indicator - shows icon with tooltip for existing notes, click to add/view */
  def noteIndicator(
      projectName: String,
      buildId: String,
      indicatorId: String,
      notes: List[ProjectNote],
      isLoggedIn: Boolean
  ): Frag =
    span(
      id := indicatorId,
      noteIndicatorContent(projectName, buildId, indicatorId, notes, isLoggedIn)
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

  // ==================== Shared Series Filter Components ====================

  /** Get CSS classes for a series button based on active state */
  def seriesButtonStyle(series: ScalaSeries, active: Boolean): String =
    series match
      case ScalaSeries.Lts33 =>
        if active then "bg-amber-600 text-white ring-2 ring-amber-400 ring-offset-1"
        else "bg-amber-100 text-amber-700 hover:bg-amber-200"
      case ScalaSeries.Lts39 =>
        if active then "bg-teal-600 text-white ring-2 ring-teal-400 ring-offset-1"
        else "bg-teal-100 text-teal-700 hover:bg-teal-200"
      case ScalaSeries.Next =>
        if active then "bg-blue-600 text-white ring-2 ring-blue-400 ring-offset-1"
        else "bg-blue-100 text-blue-700 hover:bg-blue-200"
      case ScalaSeries.All =>
        if active then "bg-gray-700 text-white ring-2 ring-gray-400 ring-offset-1"
        else "bg-gray-100 text-gray-700 hover:bg-gray-200"
