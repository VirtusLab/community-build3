package dashboard.web

import scalatags.Text.all.*
import scalatags.Text.tags2.{title as titleTag, nav, section}
import dashboard.core.*
import dashboard.data.ProjectNote
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** HTML page templates */
object Templates:
  import Components.*

  /** URL path prefix for when app is served behind a path (e.g., "/dashboard") */
  var basePath: String = ""

  /** URL-encode a string for use in URL paths */
  private def urlEncode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8)

  /** Prefix a path with the base path */
  private def path(p: String): String =
    if p.startsWith("/") then s"$basePath$p" else s"$basePath/$p"

  /** Base layout wrapper */
  def layout(pageTitle: String, pageContent: Frag): String =
    "<!DOCTYPE html>" + html(
      lang := "en",
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", attr("content") := "width=device-width, initial-scale=1"),
        titleTag(s"$pageTitle - Community Build Dashboard"),
        // Favicon - Scala organization logo
        link(rel := "icon", tpe := "image/png", href := "https://avatars.githubusercontent.com/u/57059?s=32&v=4"),
        link(rel := "apple-touch-icon", href := "https://avatars.githubusercontent.com/u/57059?s=180&v=4"),
        // Tailwind CSS via CDN
        script(src := "https://cdn.tailwindcss.com"),
        // htmx
        script(src := "https://unpkg.com/htmx.org@1.9.10"),
        // Custom styles
        tag("style")(raw("""
          .htmx-indicator { display: none; }
          .htmx-request .htmx-indicator { display: inline-flex; align-items: center; }
          .htmx-request.htmx-indicator { display: inline-flex; align-items: center; }
          .htmx-request #history-content,
          .htmx-request #results,
          .htmx-request #comparison-results { opacity: 0.5; pointer-events: none; transition: opacity 0.2s; }
          @keyframes spin { to { transform: rotate(360deg); } }
          .animate-spin { animation: spin 1s linear infinite; }
        """))
      ),
      body(
        cls := "bg-gray-50 min-h-screen",
        // Navigation
        navBar,
        // Main content
        tag("main")(
          cls := "max-w-7xl mx-auto px-4 py-8",
          pageContent
        )
      )
    ).render

  /** Navigation bar */
  private def navBar: Frag =
    nav(
      cls := "bg-white shadow-sm border-b",
      div(
        cls := "max-w-7xl mx-auto px-4",
        div(
          cls := "flex justify-between h-16",
          div(
            cls := "flex items-center",
            a(
              href := path("/"),
              cls := "text-xl font-bold text-gray-900",
              "🔧 Community Build"
            ),
            div(
              cls := "ml-10 flex space-x-4",
              navLink("/", "Build Results"),
              navLink("/projects", "Projects"),
              navLink("/compare", "Compare"),
              navLink("/docs", "API Docs")
            )
          ),
          div(
            id := "auth-status",
            cls := "flex items-center",
            attr("hx-get") := path("/auth/status"),
            attr("hx-trigger") := "load",
            attr("hx-swap") := "innerHTML",
            // Default: show sign in link (will be replaced by htmx)
            a(
              href := path("/auth/github"),
              cls := "text-gray-600 hover:text-gray-900",
              "Sign in with GitHub"
            )
          )
        )
      )
    )

  private def navLink(url: String, text: String): Frag =
    a(
      href := path(url),
      cls := "text-gray-600 hover:text-gray-900 px-3 py-2 rounded-md text-sm font-medium",
      text
    )

  /** Sort options for home page failures */
  enum FailureSort:
    case Name, Streak, Reason

  /** Home page parameters */
  final case class HomeParams(
      scalaVersion: Option[String] = None,
      buildId: Option[String] = None,
      series: ScalaSeries = ScalaSeries.All,
      reason: Option[String] = None,
      sort: FailureSort = FailureSort.Name,
      sortAsc: Boolean = true
  ):
    def queryString: String =
      val params = List(
        scalaVersion.map(v => s"scalaVersion=$v"),
        buildId.map(v => s"buildId=$v"),
        Option.when(series != ScalaSeries.All)(s"series=${series.toString}"),
        reason.map(r => s"reason=$r"),
        Option.when(sort != FailureSort.Name)(s"sort=${sort.toString}"),
        Option.when(!sortAsc)(s"sortAsc=false")
      ).flatten
      if params.isEmpty then "" else "?" + params.mkString("&")

  /** Home page with latest build results */
  def homePage(
      builds: List[BuildResult],
      scalaVersions: List[String],
      buildIds: List[String],
      params: HomeParams = HomeParams(),
      failureStreaks: Map[ProjectName, FailureStreakInfo] = Map.empty
  ): String =
    val filteredBuilds = filterHomeBuilds(builds, params)

    layout(
      "Build Results",
      div(
        id := "home-content", // Container for both selector and results
        // Version selector
        homeVersionSelector(scalaVersions, buildIds, params),

        // Results section (for htmx updates)
        div(
          id := "home-results",
          homeResultsContent(filteredBuilds, params, failureStreaks)
        )
      )
    )

  /** Partial: full home content for series changes (includes selector + results) */
  def homeContentPartial(
      builds: List[BuildResult],
      scalaVersions: List[String],
      buildIds: List[String],
      params: HomeParams,
      failureStreaks: Map[ProjectName, FailureStreakInfo] = Map.empty
  ): String =
    val filteredBuilds = filterHomeBuilds(builds, params)
    div(
      id := "home-content",
      homeVersionSelector(scalaVersions, buildIds, params),
      div(
        id := "home-results",
        homeResultsContent(filteredBuilds, params, failureStreaks)
      )
    ).render

  /** Version selector for home page */
  private def homeVersionSelector(
      scalaVersions: List[String],
      buildIds: List[String],
      params: HomeParams
  ): Frag =
    // Filter versions by selected series
    val filteredVersions = scalaVersions.filter(v => ScalaSeries.matches(params.series, v))

    div(
      id := "home-selector", // For full replacement on series change
      cls := "bg-white rounded-lg shadow p-4 mb-6 space-y-3",

      // Row 1: Series selector (uses shared styling from Components)
      div(
        cls := "flex items-center gap-2",
        span(cls := "text-sm text-gray-600", "Series:"),
        div(
          cls := "flex gap-1",
          ScalaSeries.allValues.map: series =>
            val active = params.series == series
            val colorClass = seriesButtonStyle(series, active)
            // When changing series, clear scalaVersion and buildId to get fresh data
            val newParams = HomeParams(series = series)
            button(
              cls := s"px-3 py-1 rounded text-xs font-medium transition-colors $colorClass",
              attr("hx-get") := path(s"/${newParams.queryString}"),
              attr("hx-target") := "#home-content",
              attr("hx-swap") := "outerHTML",
              attr("hx-indicator") := "#home-loading",
              ScalaSeries.label(series)
            )
        ),
        // Loading indicator
        div(
          id := "home-loading",
          cls := "htmx-indicator ml-4",
          loadingSpinner,
          span(cls := "ml-2 text-sm text-gray-500", "Loading...")
        )
      ),

      // Row 2: Version and Build selectors
      div(
        cls := "flex flex-wrap gap-4 items-center",

        // Scala version selector (filtered by series)
        div(
          cls := "flex items-center gap-2",
          tag("label")(cls := "text-sm text-gray-600", "Version:"),
          tag("select")(
            cls := "border border-gray-300 rounded-lg px-2 py-1 text-sm focus:ring-2 focus:ring-blue-500",
            attr("hx-get") := path("/"),
            attr("hx-target") := "#home-results",
            attr("hx-swap") := "innerHTML",
            attr("hx-indicator") := "#home-loading",
            attr("hx-include") := "#home-filters",
            attr("name") := "scalaVersion",
            option(
              value := "",
              if params.scalaVersion.isEmpty then attr("selected") := "selected" else frag(),
              "Latest"
            ),
            filteredVersions.map: v =>
              option(
                value := v,
                if params.scalaVersion.contains(v) then attr("selected") := "selected" else frag(),
                v
              )
          )
        ),

        // Build ID selector
        div(
          cls := "flex items-center gap-2",
          tag("label")(cls := "text-sm text-gray-600", "Build:"),
          tag("select")(
            cls := "border border-gray-300 rounded-lg px-2 py-1 text-sm focus:ring-2 focus:ring-blue-500",
            attr("hx-get") := path("/"),
            attr("hx-target") := "#home-results",
            attr("hx-swap") := "innerHTML",
            attr("hx-indicator") := "#home-loading",
            attr("hx-include") := "#home-filters",
            attr("name") := "buildId",
            option(value := "", if params.buildId.isEmpty then attr("selected") := "selected" else frag(), "Latest"),
            buildIds
              .take(20)
              .map: id =>
                option(
                  value := id,
                  if params.buildId.contains(id) then attr("selected") := "selected" else frag(),
                  id
                )
          )
        ),

        // Hidden inputs for htmx includes
        div(
          id := "home-filters",
          cls := "hidden",
          input(
            tpe := "hidden",
            name := "series",
            value := (if params.series == ScalaSeries.All then "" else params.series.toString)
          ),
          input(tpe := "hidden", name := "reason", value := params.reason.getOrElse(""))
        )
      )
    )

  /** Filter builds based on home params (reason filtering happens in homeResultsContent) */
  private def filterHomeBuilds(builds: List[BuildResult], params: HomeParams): List[BuildResult] =
    builds.filter(build => ScalaSeries.matches(params.series, build.scalaVersion))

  /** Home page results content */
  def homeResultsContent(
      builds: List[BuildResult],
      params: HomeParams = HomeParams(),
      failureStreaks: Map[ProjectName, FailureStreakInfo] = Map.empty
  ): Frag =
    // If no builds and no version/series selected, show prompt to select one
    if builds.isEmpty && params.scalaVersion.isEmpty && params.series == ScalaSeries.All then
      return div(
        cls := "bg-white rounded-lg shadow p-12 text-center",
        div(
          cls := "text-gray-400 mb-4",
          raw(
            """<svg class="w-16 h-16 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"></path></svg>"""
          )
        ),
        h2(cls := "text-xl font-semibold text-gray-700 mb-2", "Select a Scala Version"),
        p(cls := "text-gray-500", "Choose a Scala version or series above to view build results")
      )

    // If series selected but no builds available for it (only if no reason filter applied)
    if builds.isEmpty && params.series != ScalaSeries.All && params.reason.isEmpty then
      val seriesName = ScalaSeries.label(params.series)
      return div(
        cls := "bg-white rounded-lg shadow p-12 text-center",
        div(
          cls := "text-gray-400 mb-4",
          raw(
            """<svg class="w-16 h-16 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"></path></svg>"""
          )
        ),
        h2(cls := "text-xl font-semibold text-gray-700 mb-2", s"No builds for $seriesName"),
        p(cls := "text-gray-500", "No versions are available for this series yet")
      )

    // Get all failures first (for stats), then apply reason filter for display
    val allFailures = builds.filter(_.status == BuildStatus.Failure)
    val failures = params.reason match
      case Some(r) => allFailures.filter(_.failureReasons.exists(_.toString == r))
      case None    => allFailures
    val successes = builds.count(_.status == BuildStatus.Success)

    // Sort failures based on params.sort and direction
    val baseSorted = params.sort match
      case FailureSort.Streak =>
        failures.sortBy(f => failureStreaks.get(f.projectName).map(_.days).getOrElse(0L))
      case FailureSort.Reason =>
        failures.sortBy(f => f.failureReasons.headOption.map(_.toString).getOrElse("Z"))
      case FailureSort.Name =>
        failures.sortBy(_.projectName: String)
    val sortedFailures = if params.sortAsc then baseSorted else baseSorted.reverse

    frag(
      // Summary stats (show unfiltered counts)
      div(
        cls := "grid grid-cols-1 md:grid-cols-4 gap-4 mb-6",
        statCard("Total", builds.length.toString, "text-gray-900"),
        statCard("Passing", successes.toString, "text-emerald-600"),
        statCard("Failing", allFailures.length.toString, "text-red-600"),
        // Build info
        builds.headOption.map: b =>
          div(
            cls := "bg-white rounded-lg shadow p-4",
            p(cls := "text-sm text-gray-500", "Build Info"),
            p(cls := "text-sm font-medium truncate", title := b.buildId, s"${b.scalaVersion}"),
            p(cls := "text-xs text-gray-400 truncate", b.buildId)
          )
      ),

      // Reason filter
      div(
        cls := "flex flex-wrap gap-2 mb-4 items-center",
        span(cls := "text-sm text-gray-500", "Filter by reason:"),
        List(
          None -> "All",
          Some("Compilation") -> "Compile",
          Some("TestCompilation") -> "Test Compile",
          Some("Tests") -> "Tests",
          Some("Scaladoc") -> "Scaladoc",
          Some("Build") -> "Build"
        ).map: (reasonOpt, label) =>
          val active = params.reason == reasonOpt
          val colorClass = reasonOpt match
            case Some("Compilation") | Some("TestCompilation") =>
              if active then "bg-red-600 text-white" else "bg-red-100 text-red-700 hover:bg-red-200"
            case Some("Tests") =>
              if active then "bg-orange-600 text-white" else "bg-orange-100 text-orange-700 hover:bg-orange-200"
            case Some("Scaladoc") =>
              if active then "bg-purple-600 text-white" else "bg-purple-100 text-purple-700 hover:bg-purple-200"
            case Some("Build") =>
              if active then "bg-gray-600 text-white" else "bg-gray-200 text-gray-700 hover:bg-gray-300"
            case None =>
              if active then "bg-purple-600 text-white" else "bg-purple-100 text-purple-700 hover:bg-purple-200"
            case _ =>
              if active then "bg-purple-600 text-white" else "bg-purple-100 text-purple-700 hover:bg-purple-200"
          val newParams = params.copy(reason = reasonOpt)
          button(
            cls := s"px-3 py-1 rounded text-xs font-medium transition-colors $colorClass",
            attr("hx-get") := path(s"/${newParams.queryString}"),
            attr("hx-target") := "#home-results",
            attr("hx-swap") := "innerHTML",
            attr("hx-indicator") := "#home-loading",
            label
          )
      ),

      // Failures section
      section(
        cls := "mb-8",
        // Header with sort options
        div(
          cls := "flex flex-wrap justify-between items-center mb-4 gap-2",
          h2(cls := "text-xl font-semibold", s"Failures (${failures.length})"),
          // Sort buttons with direction toggle
          if failures.nonEmpty then
            div(
              cls := "flex items-center gap-2",
              span(cls := "text-sm text-gray-500", "Sort by:"),
              List(
                FailureSort.Name -> "Name",
                FailureSort.Streak -> "Streak",
                FailureSort.Reason -> "Reason"
              ).map: (sortOpt, label) =>
                val active = params.sort == sortOpt
                // If clicking same button, toggle direction; otherwise default to ascending
                val newParams =
                  if active then params.copy(sortAsc = !params.sortAsc)
                  else params.copy(sort = sortOpt, sortAsc = true)
                val dirIndicator = if active then (if params.sortAsc then " ↑" else " ↓") else ""
                button(
                  cls := s"px-3 py-1 rounded text-xs font-medium transition-colors ${
                      if active then "bg-gray-700 text-white" else "bg-gray-100 text-gray-600 hover:bg-gray-200"
                    }",
                  attr("hx-get") := path(s"/${newParams.queryString}"),
                  attr("hx-target") := "#home-results",
                  attr("hx-swap") := "innerHTML",
                  attr("hx-indicator") := "#home-loading",
                  s"$label$dirIndicator"
                )
            )
          else frag()
        ),
        if failures.isEmpty then
          if params.reason.isDefined then emptyState("No failures matching this filter")
          else emptyState("All projects passing! 🎉")
        else
          div(
            cls := "grid gap-4",
            sortedFailures.map(f => buildCardWithDuration(f, failureStreaks.get(f.projectName)))
          )
      )
    )

  /** Partial: home results for htmx updates */
  def homeResultsPartial(
      builds: List[BuildResult],
      params: HomeParams = HomeParams(),
      failureStreaks: Map[ProjectName, FailureStreakInfo] = Map.empty
  ): String =
    val filteredBuilds = filterHomeBuilds(builds, params)
    homeResultsContent(filteredBuilds, params, failureStreaks).render

  /** Comparison page */
  def comparePage(
      scalaVersions: List[String],
      buildIds: List[String],
      result: Option[ComparisonResult],
      params: CompareParams = CompareParams(None, None, None, None),
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      isLoggedIn: Boolean = false
  ): String =
    // Filter versions by selected series
    val filteredVersions = scalaVersions.filter(v => ScalaSeries.matches(params.series, v))

    layout(
      "Compare Builds",
      div(
        h1(cls := "text-2xl font-bold mb-6", "Compare Builds"),

        // Comparison form (with series filtering)
        div(
          id := "compare-form-container",
          compareForm(filteredVersions, buildIds, params)
        ),

        // Results container (always present for htmx targeting)
        div(
          id := "comparison-results",
          result
            .map(r => comparisonResultsContent(r, params, notesMap, isLoggedIn))
            .getOrElse(
              div(
                cls := "text-center py-12 text-gray-500",
                "Select versions or build IDs to compare"
              )
            )
        )
      )
    )

  /** Partial: compare form for htmx updates (when series changes) */
  def compareFormPartial(
      scalaVersions: List[String],
      buildIds: List[String],
      params: CompareParams
  ): String =
    // Filter versions by selected series
    val filteredVersions = scalaVersions.filter(v => ScalaSeries.matches(params.series, v))
    compareForm(filteredVersions, buildIds, params).render

  /** Comparison form */
  private def compareForm(scalaVersions: List[String], buildIds: List[String], params: CompareParams): Frag =
    form(
      id := "compare-form",
      cls := "bg-white rounded-lg shadow p-6 mb-8",
      attr("hx-get") := path("/compare"),
      attr("hx-target") := "#comparison-results",
      attr("hx-swap") := "innerHTML",

      // Series filter row (HTMX-based)
      div(
        cls := "mb-4 pb-4 border-b border-gray-200 flex items-center gap-2",
        span(cls := "text-sm text-gray-600", "Series:"),
        div(
          cls := "flex gap-1",
          ScalaSeries.allValues.map: series =>
            val active = params.series == series
            val colorClass = seriesButtonStyle(series, active)
            val seriesParam = if series == ScalaSeries.All then "" else series.toString
            button(
              tpe := "button",
              cls := s"px-3 py-1 rounded text-xs font-medium transition-colors $colorClass",
              attr("hx-get") := path(s"/compare/form?series=$seriesParam"),
              attr("hx-target") := "#compare-form-container",
              attr("hx-swap") := "innerHTML",
              attr("hx-indicator") := "#compare-loading",
              ScalaSeries.label(series)
            )
        ),
        // Loading indicator
        div(
          id := "compare-loading",
          cls := "htmx-indicator ml-4",
          loadingSpinner,
          span(cls := "ml-2 text-sm text-gray-500", "Loading...")
        )
      ),
      div(
        cls := "grid grid-cols-1 md:grid-cols-[1fr_auto_1fr] gap-4 items-center",
        // Base selection
        div(
          h3(cls := "font-medium mb-2", "Base"),
          p(cls := "text-xs text-gray-500 mb-3", "The version to compare against (previous)"),
          div(
            cls := "space-y-3",
            selectField("baseScalaVersion", "Scala Version", scalaVersions, params.baseScalaVersion),
            div(cls := "text-center text-gray-400", "or"),
            selectField("baseBuildId", "Build ID", buildIds, params.baseBuildId)
          )
        ),
        // Swap button
        div(
          cls := "hidden md:flex flex-col items-center justify-center pt-8",
          button(
            tpe := "button",
            cls := "p-2 rounded-full bg-gray-100 hover:bg-gray-200 text-gray-600 hover:text-gray-800 transition-colors",
            title := "Swap Base and Target",
            attr("onclick") := "swapCompareValues()",
            // Double-headed arrow icon
            raw(
              """<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"/></svg>"""
            )
          ),
          span(cls := "text-xs text-gray-400 mt-1", "Swap")
        ),
        // Target selection
        div(
          h3(cls := "font-medium mb-2", "Target"),
          p(cls := "text-xs text-gray-500 mb-3", "The version to compare with (current)"),
          div(
            cls := "space-y-3",
            selectField("targetScalaVersion", "Scala Version", scalaVersions, params.targetScalaVersion),
            div(cls := "text-center text-gray-400", "or"),
            selectField("targetBuildId", "Build ID", buildIds, params.targetBuildId)
          )
        )
      ),
      // Mobile swap button (shown on small screens)
      div(
        cls := "md:hidden flex justify-center my-2",
        button(
          tpe := "button",
          cls := "px-4 py-2 rounded-lg bg-gray-100 hover:bg-gray-200 text-gray-600 hover:text-gray-800 transition-colors text-sm flex items-center gap-2",
          attr("onclick") := "swapCompareValues()",
          raw(
            """<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"/></svg>"""
          ),
          "Swap Base ↔ Target"
        )
      ),
      div(
        cls := "mt-6 flex justify-end gap-3",
        button(
          tpe := "submit",
          cls := "bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700 transition-colors",
          "Compare",
          span(cls := "htmx-indicator ml-2", loadingSpinner)
        )
      ),
      // JavaScript for swap and copy link
      script(raw(swapCompareScript))
    )

  /** Build a shareable URL for comparison */
  private def buildCompareUrl(params: CompareParams): String =
    val queryParams = List(
      params.baseScalaVersion.map(v => s"baseScalaVersion=${urlEncode(v)}"),
      params.baseBuildId.map(v => s"baseBuildId=${urlEncode(v)}"),
      params.targetScalaVersion.map(v => s"targetScalaVersion=${urlEncode(v)}"),
      params.targetBuildId.map(v => s"targetBuildId=${urlEncode(v)}")
    ).flatten
    if queryParams.isEmpty then path("/compare")
    else path(s"/compare?${queryParams.mkString("&")}")

  /** JavaScript for swapping compare form values and copying share link */
  private val swapCompareScript: String = """
    function swapCompareValues() {
      const baseVersion = document.querySelector('select[name="baseScalaVersion"]');
      const targetVersion = document.querySelector('select[name="targetScalaVersion"]');
      const baseBuildId = document.querySelector('select[name="baseBuildId"]');
      const targetBuildId = document.querySelector('select[name="targetBuildId"]');
      
      // Swap Scala versions
      const tempVersion = baseVersion.value;
      baseVersion.value = targetVersion.value;
      targetVersion.value = tempVersion;
      
      // Swap Build IDs
      const tempBuildId = baseBuildId.value;
      baseBuildId.value = targetBuildId.value;
      targetBuildId.value = tempBuildId;
    }
    
    function copyShareLink(path) {
      const url = window.location.origin + path;
      navigator.clipboard.writeText(url).then(() => {
        const btn = document.getElementById('share-btn');
        const original = btn.innerHTML;
        btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7"/></svg><span>Copied!</span>';
        btn.className = btn.className.replace('bg-gray-100 text-gray-600', 'bg-green-100 text-green-700');
        setTimeout(() => {
          btn.innerHTML = original;
          btn.className = btn.className.replace('bg-green-100 text-green-700', 'bg-gray-100 text-gray-600');
        }, 2000);
      });
    }
  """

  private def selectField(name: String, label: String, options: List[String], selected: Option[String]): Frag =
    div(
      tag("label")(cls := "block text-sm text-gray-600 mb-1", label),
      tag("select")(
        attr("name") := name,
        cls := "w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
        option(value := "", if selected.isEmpty then attr("selected") := "selected" else frag(), "Select..."),
        options.map: v =>
          option(value := v, if selected.contains(v) then attr("selected") := "selected" else frag(), v)
      )
    )

  /** Parameters for comparison filtering */
  final case class CompareParams(
      baseScalaVersion: Option[String],
      baseBuildId: Option[String],
      targetScalaVersion: Option[String],
      targetBuildId: Option[String],
      filter: String = "all",
      reason: Option[String] = None,
      series: ScalaSeries = ScalaSeries.All
  )

  /** Comparison results content (without outer container) */
  def comparisonResultsContent(
      result: ComparisonResult,
      params: CompareParams,
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      isLoggedIn: Boolean = false
  ): Frag =
    // Build shareable URL from params
    val shareUrl = buildCompareUrl(params)

    frag(
      // Hidden inputs to store comparison parameters for htmx requests
      div(
        id := "comparison-params",
        input(tpe := "hidden", name := "baseScalaVersion", value := params.baseScalaVersion.getOrElse("")),
        input(tpe := "hidden", name := "baseBuildId", value := params.baseBuildId.getOrElse("")),
        input(tpe := "hidden", name := "targetScalaVersion", value := params.targetScalaVersion.getOrElse("")),
        input(tpe := "hidden", name := "targetBuildId", value := params.targetBuildId.getOrElse("")),
        input(tpe := "hidden", name := "filter", value := params.filter),
        input(tpe := "hidden", name := "reason", value := params.reason.getOrElse(""))
      ),

      // Header with Copy link button
      div(
        cls := "flex justify-between items-center mb-4",
        h3(cls := "text-lg font-semibold text-gray-700", "Comparison Results"),
        button(
          id := "share-btn",
          tpe := "button",
          cls := "flex items-center gap-2 px-3 py-1.5 text-sm text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors",
          attr("onclick") := s"copyShareLink('$shareUrl')",
          raw(
            """<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"/></svg>"""
          ),
          span("Copy link")
        )
      ),

      // Summary
      div(
        cls := "grid grid-cols-2 md:grid-cols-5 gap-4 mb-6",
        statCardWithTooltip(
          "Total",
          (result.newFailures.length + result.newFixes.length + result.stillFailing.length + result.stillPassing).toString,
          "text-blue-600",
          "Total number of projects compared"
        ),
        statCardWithTooltip(
          "New Failures",
          result.newFailures.length.toString,
          "text-red-600",
          "Projects that passed in Base but fail in Target (regressions)"
        ),
        statCardWithTooltip(
          "New Fixes",
          result.newFixes.length.toString,
          "text-emerald-600",
          "Projects that failed in Base but pass in Target (improvements)"
        ),
        statCardWithTooltip(
          "Still Failing",
          result.stillFailing.length.toString,
          "text-yellow-600",
          "Projects that fail in both Base and Target"
        ),
        statCardWithTooltip(
          "Still Passing",
          result.stillPassing.toString,
          "text-gray-600",
          "Projects that pass in both Base and Target"
        )
      ),

      // Filter buttons
      filterButtons(params.filter, params.reason),

      // Results table
      div(
        id := "results",
        comparisonTable(result, params.filter, params.reason, notesMap, isLoggedIn)
      )
    )

  /** Comparison results with container (for full page render) */
  def comparisonResults(
      result: ComparisonResult,
      params: CompareParams,
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      isLoggedIn: Boolean = false
  ): Frag =
    div(
      id := "comparison-results",
      comparisonResultsContent(result, params, notesMap, isLoggedIn)
    )

  /** Comparison results table with pre-fetched notes (avoids N+1 HTTP requests) */
  def comparisonTable(
      result: ComparisonResult,
      filter: String,
      reason: Option[String],
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      isLoggedIn: Boolean = false
  ): Frag =
    // Apply filter
    val (newFailures, newFixes, stillFailing) = filter match
      case "new-failures"  => (result.newFailures, Nil, Nil)
      case "new-fixes"     => (Nil, result.newFixes, Nil)
      case "still-failing" => (Nil, Nil, result.stillFailing)
      case _               => (result.newFailures, result.newFixes, result.stillFailing)

    // Apply reason filter
    def filterByReason(diffs: List[ProjectDiff]): List[ProjectDiff] =
      reason match
        case Some(r) => diffs.filter(_.failureReasons.exists(_.toString == r))
        case None    => diffs

    val filteredNewFailures = filterByReason(newFailures)
    val filteredNewFixes = filterByReason(newFixes)
    val filteredStillFailing = filterByReason(stillFailing)

    // Helper to get notes for a project
    def notesFor(diff: ProjectDiff): List[ProjectNote] =
      notesMap.getOrElse(diff.projectName: String, Nil)

    if filteredNewFailures.isEmpty && filteredNewFixes.isEmpty && filteredStillFailing.isEmpty then
      emptyState("No matching results")
    else
      div(
        cls := "bg-white rounded-lg shadow overflow-hidden",
        table(
          cls := "min-w-full divide-y divide-gray-200",
          thead(
            cls := "bg-gray-50",
            tr(
              th(cls := "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase", ""),
              th(cls := "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase", "Project"),
              th(cls := "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase", "Version"),
              th(cls := "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase", "Status"),
              th(cls := "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase", "Reason"),
              th(cls := "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase", "Logs"),
              th(cls := "px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase", "Notes")
            )
          ),
          tbody(
            cls := "divide-y divide-gray-200",
            filteredNewFailures.map(d => diffRow(d, "new-failure", notesFor(d), isLoggedIn)),
            filteredNewFixes.map(d => diffRow(d, "new-fix", notesFor(d), isLoggedIn)),
            filteredStillFailing.map(d => diffRow(d, "still-failing", notesFor(d), isLoggedIn))
          )
        )
      )

  /** Partial: filtered table for htmx */
  def comparisonTablePartial(
      result: ComparisonResult,
      filter: String,
      reason: Option[String],
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      isLoggedIn: Boolean = false
  ): String =
    comparisonTable(result, filter, reason, notesMap, isLoggedIn).render

  /** Parameters for history filtering */
  final case class HistoryParams(
      projectName: String,
      series: ScalaSeries = ScalaSeries.Next,
      excludeSnapshots: Boolean = true,
      excludeNightlies: Boolean = false
  )

  /** Project history page */
  def projectHistoryPage(
      history: ProjectHistory,
      projectNotes: List[ProjectNote],
      buildNotesMap: Map[String, List[ProjectNote]] = Map.empty,
      params: HistoryParams = HistoryParams(""),
      canEdit: Boolean = false
  ): String =
    val effectiveParams = params.copy(projectName = history.projectName: String)
    val filteredEntries = filterHistoryEntries(history.entries, effectiveParams)
    val filteredHistory = computeFilteredStats(history, filteredEntries)
    val versionStats = computeVersionStats(filteredEntries)

    layout(
      s"${history.projectName} History",
      div(
        // Header with project name (static)
        div(
          cls := "mb-4",
          h1(cls := "text-2xl font-bold", history.projectName: String)
        ),

        // Status header (updates with filters)
        div(
          id := "history-status",
          historyStatusHeader(filteredHistory, versionStats)
        ),

        // Notes section - always visible, but add/delete only for users with edit access
        if projectNotes.nonEmpty || canEdit then
          section(
            cls := "mb-8",
            div(
              cls := "flex justify-between items-center mb-4",
              h2(cls := "text-xl font-semibold", "Project Notes"),
              // Add button only for users with edit access
              if canEdit then
                button(
                  cls := "text-blue-600 hover:text-blue-800 text-sm",
                  attr("hx-get") := path(s"/projects/${history.projectName: String}/notes/new"),
                  attr("hx-target") := "#notes-container",
                  attr("hx-swap") := "afterbegin",
                  "+ Add Note"
                )
              else frag()
            ),
            div(
              id := "notes-container",
              if projectNotes.isEmpty then p(cls := "text-gray-500 text-sm", "No project-level notes yet")
              else projectNotes.map(n => projectNoteCard(n, canEdit))
            )
          )
        else frag(),

        // Timeline with filters - wrapped for htmx updates
        div(
          id := "history-section",
          historySectionContent(filteredEntries, effectiveParams, buildNotesMap, canEdit)
        )
      )
    )

  /** History filter buttons */
  private def historyFilters(params: HistoryParams): Frag =
    val projectPath = params.projectName

    def filterUrl(
        series: ScalaSeries = params.series,
        excludeSnapshots: Boolean = params.excludeSnapshots,
        excludeNightlies: Boolean = params.excludeNightlies
    ): String =
      path(
        s"/projects/$projectPath/history/filter?series=${series}&excludeSnapshots=$excludeSnapshots&excludeNightlies=$excludeNightlies"
      )

    div(
      cls := "flex flex-wrap gap-4 items-center",

      // Series selector (uses shared list and styling from Components)
      div(
        cls := "flex gap-1 items-center",
        span(cls := "text-sm text-gray-500 self-center mr-2", "Series:"),
        ScalaSeries.concreteValues.map: series =>
          val active = params.series == series
          val colorClass = seriesButtonStyle(series, active)
          button(
            cls := s"px-3 py-1 rounded text-xs font-medium transition-colors $colorClass",
            attr("hx-get") := filterUrl(series = series),
            attr("hx-target") := "#history-section",
            attr("hx-swap") := "innerHTML",
            attr("hx-indicator") := "#history-loading",
            title := ScalaSeries.description(series),
            ScalaSeries.label(series)
          )
      ),

      // Filter toggles
      div(
        cls := "flex gap-2 ml-2 border-l pl-4 items-center",
        span(cls := "text-sm text-gray-500 mr-2", "Hide:"),
        // Snapshot toggle button
        button(
          cls := s"px-3 py-1 rounded text-xs font-medium transition-colors ${
              if params.excludeSnapshots then "bg-gray-700 text-white"
              else "bg-gray-100 text-gray-600 hover:bg-gray-200"
            }",
          attr("hx-get") := filterUrl(excludeSnapshots = !params.excludeSnapshots),
          attr("hx-target") := "#history-section",
          attr("hx-swap") := "innerHTML",
          attr("hx-indicator") := "#history-loading",
          title := "Snapshot versions like 3.8.0-RC4-bin-20251230-fab225a",
          "Snapshots"
        ),
        // Nightly toggle button
        button(
          cls := s"px-3 py-1 rounded text-xs font-medium transition-colors ${
              if params.excludeNightlies then "bg-gray-700 text-white"
              else "bg-gray-100 text-gray-600 hover:bg-gray-200"
            }",
          attr("hx-get") := filterUrl(excludeNightlies = !params.excludeNightlies),
          attr("hx-target") := "#history-section",
          attr("hx-swap") := "innerHTML",
          attr("hx-indicator") := "#history-loading",
          title := "Nightly versions like 3.8.1-RC1-bin-20251228-e73ff2c-NIGHTLY",
          "Nightlies"
        )
      ),

      // Loading indicator
      div(
        id := "history-loading",
        cls := "htmx-indicator ml-4",
        loadingSpinner,
        span(cls := "ml-2 text-sm text-gray-500", "Loading...")
      )
    )

  /** Status header for project history (shown below project name, updates with filters) */
  private def historyStatusHeader(filteredHistory: ProjectHistory, versionStats: Option[String]): Frag =
    div(
      cls := "mb-6 flex flex-wrap gap-4",
      // Failure status
      if filteredHistory.currentlyFailing then
        div(
          cls := "text-red-600",
          s"⚠️ Currently failing",
          filteredHistory.failingForDays.map(d => s" for $d days").getOrElse("")
        )
      else div(cls := "text-emerald-600", "✅ Currently passing"),
      // Version stats
      versionStats.map: stats =>
        div(
          cls := "text-gray-500 border-l pl-4",
          stats
        )
    )

  /** Filter history entries based on params */
  private def filterHistoryEntries(
      entries: List[ProjectHistoryEntry],
      params: HistoryParams
  ): List[ProjectHistoryEntry] =
    entries.filter: entry =>
      val matchesSeries = ScalaSeries.fromScalaVersion(entry.scalaVersion) == params.series
      val matchesVersionType =
        VersionType.shouldShow(entry.scalaVersion, params.excludeSnapshots, params.excludeNightlies)
      matchesSeries && matchesVersionType

  /** Compute version statistics for filtered entries */
  private def computeVersionStats(entries: List[ProjectHistoryEntry]): Option[String] =
    if entries.isEmpty then None
    else
      val versions = entries.map(_.version).distinct
      if versions.size <= 1 then Some(s"Single version: ${versions.headOption.getOrElse("unknown")}")
      else
        // Find the most recent version change
        val latestVersion = entries.head.version
        val versionChangeIdx = entries.indexWhere(_.version != latestVersion)
        if versionChangeIdx > 0 then
          val previousVersionEntry = entries(versionChangeIdx)
          val daysSinceRelease = java.time.Duration
            .between(previousVersionEntry.timestamp, entries.head.timestamp)
            .toDays
          Some(s"v$latestVersion released $daysSinceRelease days ago (${versions.size} versions total)")
        else Some(s"${versions.size} versions")

  /** Compute failure stats for filtered entries */
  private def computeFilteredStats(original: ProjectHistory, filtered: List[ProjectHistoryEntry]): ProjectHistory =
    if filtered.isEmpty then
      original.copy(entries = filtered, currentlyFailing = false, failingSince = None, failingForDays = None)
    else
      val latestEntry = filtered.head
      val currentlyFailing = latestEntry.status == BuildStatus.Failure
      val (failingSince, failingForDays) = if currentlyFailing then
        // Find when the failure streak started in filtered entries
        val failureStreak = filtered.takeWhile(_.status == BuildStatus.Failure)
        val since = failureStreak.lastOption.map(_.timestamp)
        val days: Option[Long] = since.map: start =>
          java.time.Duration.between(start, java.time.Instant.now()).toDays
        (since, days)
      else (None, None)

      original.copy(
        entries = filtered,
        currentlyFailing = currentlyFailing,
        failingSince = failingSince,
        failingForDays = failingForDays
      )

  private val HistoryPageSize = 30 // Load 30 entries at a time

  /** History section content (filters + timeline) with infinite scroll */
  private def historySectionContent(
      filteredEntries: List[ProjectHistoryEntry],
      params: HistoryParams,
      notesMap: Map[String, List[ProjectNote]],
      canEdit: Boolean,
      offset: Int = 0
  ): Frag =
    val visibleEntries = filteredEntries.slice(offset, offset + HistoryPageSize)
    val hasMore = offset + HistoryPageSize < filteredEntries.length
    val nextOffset = offset + HistoryPageSize

    // Helper to get notes for a specific build
    def notesFor(entry: ProjectHistoryEntry): List[ProjectNote] =
      notesMap.getOrElse(entry.buildId, Nil)

    section(
      // Header with filters
      div(
        cls := "flex flex-wrap justify-between items-center mb-4 gap-4",
        div(
          cls := "flex items-center gap-4",
          h2(cls := "text-xl font-semibold", "Build History"),
          // Entry count
          span(cls := "text-sm text-gray-400", s"(${filteredEntries.length} entries)")
        ),
        // Filter controls
        historyFilters(params)
      ),
      div(
        id := "history-content",
        cls := "bg-white rounded-lg shadow p-6",
        if filteredEntries.isEmpty then emptyState("No build history for this filter")
        else
          div(
            id := "history-entries",
            visibleEntries.map(e => historyEntry(e, notesFor(e), canEdit))
          )
        ,
        // Load more trigger - triggers when scrolled into view
        loadMoreTrigger(filteredEntries.length, nextOffset, hasMore, params)
      )
    )

  /** Load more trigger for infinite scroll */
  private def loadMoreTrigger(
      totalEntries: Int,
      nextOffset: Int,
      hasMore: Boolean,
      params: HistoryParams
  ): Frag =
    if hasMore then
      val filterUrl = path(
        s"/projects/${params.projectName}/history/more?series=${params.series}&excludeSnapshots=${params.excludeSnapshots}&excludeNightlies=${params.excludeNightlies}&offset=$nextOffset"
      )
      div(
        id := "history-load-more",
        cls := "pt-4 text-center text-gray-500 text-sm border-t mt-4",
        attr("hx-get") := filterUrl,
        attr("hx-trigger") := "revealed",
        attr("hx-swap") := "outerHTML",
        loadingSpinner,
        span(cls := "ml-2", s"Loading more... (${math.min(nextOffset, totalEntries)} of $totalEntries)")
      )
    else if totalEntries > 0 then
      div(
        cls := "pt-4 text-center text-gray-500 text-sm border-t mt-4",
        s"Showing all $totalEntries builds"
      )
    else frag()

  /** Partial: more history entries for infinite scroll */
  def historyMoreEntries(
      history: ProjectHistory,
      params: HistoryParams,
      offset: Int,
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      canEdit: Boolean = false
  ): String =
    val filteredEntries = filterHistoryEntries(history.entries, params)
    val visibleEntries = filteredEntries.slice(offset, offset + HistoryPageSize)
    val hasMore = offset + HistoryPageSize < filteredEntries.length
    val nextOffset = offset + HistoryPageSize

    // Helper to get notes for a specific build
    def notesFor(entry: ProjectHistoryEntry): List[ProjectNote] =
      notesMap.getOrElse(entry.buildId, Nil)

    val entries = visibleEntries.map(e => historyEntry(e, notesFor(e), canEdit))

    frag(
      entries,
      loadMoreTrigger(filteredEntries.length, nextOffset, hasMore, params)
    ).render

  /** Partial: history section for htmx updates (includes filters) */
  def historyContentPartial(
      history: ProjectHistory,
      params: HistoryParams,
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      canEdit: Boolean = false
  ): String =
    val filteredEntries = filterHistoryEntries(history.entries, params)
    val filteredHistory = computeFilteredStats(history, filteredEntries)
    val versionStats = computeVersionStats(filteredEntries)
    frag(
      // OOB update for status header
      div(
        id := "history-status",
        attr("hx-swap-oob") := "true",
        historyStatusHeader(filteredHistory, versionStats)
      ),
      // Main content
      historySectionContent(filteredEntries, params, notesMap, canEdit, 0)
    ).render

  /** Log viewer page */
  def logsPage(projectName: String, buildId: String, logs: ParsedLogs): String =
    layout(
      s"Logs - $projectName",
      div(
        h1(cls := "text-2xl font-bold mb-2", s"Build Logs"),
        p(cls := "text-gray-500 mb-6", s"$projectName • $buildId"),

        // Stats
        div(
          cls := "grid grid-cols-3 gap-4 mb-6",
          statCard("Errors", logs.errorCount.toString, "text-red-600"),
          statCard("Warnings", logs.warningCount.toString, "text-yellow-600"),
          statCard("Total Lines", logs.entries.length.toString, "text-gray-600")
        ),

        // Severity filter
        severityFilter("all", path(s"/projects/$projectName/builds/${urlEncode(buildId)}/logs")),

        // Log content
        div(
          id := "log-content",
          cls := "bg-white rounded-lg shadow border border-gray-200 overflow-x-auto",
          if logs.entries.isEmpty then p(cls := "text-gray-500 p-4", "No logs available")
          else logs.entries.map(logEntry)
        )
      )
    )

  /** Partial: log content for htmx updates */
  def logsPartial(logs: ParsedLogs): String =
    div(
      if logs.entries.isEmpty then p(cls := "text-gray-500 p-4", "No matching log entries")
      else logs.entries.map(logEntry)
    ).render

  /** Error page when logs are not available */
  def logsErrorPage(projectName: String, buildId: String, errorMessage: String): String =
    val historyUrl = projectName.split("/") match
      case Array(org, repo) => path(s"/projects/$org/$repo/history")
      case _                => path("/")

    layout(
      s"Logs Not Found - $projectName",
      div(
        cls := "max-w-2xl mx-auto text-center py-12",
        // Error icon
        div(cls := "text-6xl mb-6", "📭"),

        // Title
        h1(cls := "text-2xl font-bold text-gray-900 mb-2", "Logs Not Available"),

        // Project info
        p(cls := "text-gray-500 mb-6", s"$projectName • $buildId"),

        // Error message box
        div(
          cls := "bg-amber-50 border border-amber-200 rounded-lg p-4 mb-6 text-left",
          p(cls := "text-amber-800", errorMessage)
        ),

        // Explanation
        div(
          cls := "text-gray-600 text-sm mb-8 space-y-2",
          p("This can happen for several reasons:"),
          ul(
            cls := "list-disc list-inside text-left mx-auto max-w-md",
            li("The build is very old and logs were not collected"),
            li("The logs index was not available when this build ran"),
            li("The build ID doesn't exist for this project")
          )
        ),

        // Actions
        div(
          cls := "flex gap-4 justify-center",
          a(
            href := historyUrl,
            cls := "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors",
            "← Back to Project History"
          ),
          a(
            href := path("/"),
            cls := "px-4 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors",
            "Go to Homepage"
          )
        )
      )
    )

  /** Partial: comparison results for htmx updates */
  def comparisonResultsPartial(
      result: ComparisonResult,
      params: CompareParams,
      notesMap: Map[String, List[ProjectNote]] = Map.empty,
      isLoggedIn: Boolean = false
  ): String =
    comparisonResultsContent(result, params, notesMap, isLoggedIn).render

  /** Stat card component */
  private def statCard(label: String, value: String, valueColor: String): Frag =
    div(
      cls := "bg-white rounded-lg shadow p-6",
      p(cls := "text-sm text-gray-500", label),
      p(cls := s"text-3xl font-bold $valueColor", value)
    )

  /** Stat card with tooltip explanation */
  private def statCardWithTooltip(label: String, value: String, valueColor: String, tooltip: String): Frag =
    div(
      cls := "bg-white rounded-lg shadow p-6 relative group",
      p(
        cls := "text-sm text-gray-500 flex items-center gap-1 cursor-help",
        label,
        span(cls := "text-gray-400 text-xs", "ⓘ"),
        // Tooltip that appears on hover
        span(
          cls := "absolute left-1/2 -translate-x-1/2 bottom-full mb-2 px-3 py-2 bg-gray-900 text-white text-xs rounded-lg opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all duration-200 z-10 w-56 text-center",
          tooltip,
          // Tooltip arrow
          span(cls := "absolute left-1/2 -translate-x-1/2 top-full border-4 border-transparent border-t-gray-900")
        )
      ),
      p(cls := s"text-3xl font-bold $valueColor", value)
    )

  // ==================== Projects List Page ====================

  /** Projects list page - loads all projects at once */
  def projectsListPage(projectsList: ProjectsList): String =
    layout(
      "All Projects",
      div(
        h1(cls := "text-2xl font-bold mb-6", "All Projects"),

        // Stats
        div(
          cls := "grid grid-cols-1 md:grid-cols-3 gap-6 mb-8",
          statCard("Total Projects", projectsList.totalCount.toString, "text-gray-900"),
          statCard("Passing", projectsList.passingCount.toString, "text-emerald-600"),
          statCard("Failing", projectsList.failingCount.toString, "text-red-600")
        ),

        // Projects list
        if projectsList.projects.isEmpty then emptyState("No projects found")
        else
          frag(
            div(
              cls := "bg-white rounded-lg shadow overflow-x-auto",
              table(
                id := "projects-table",
                cls := "min-w-full divide-y divide-gray-200",
                thead(
                  cls := "bg-gray-50 sticky top-0",
                  tr(
                    sortableHeader("Project", "name", "min-w-48 text-left"),
                    th(cls := "w-24 px-3 py-2 text-left text-xs font-medium text-gray-500 uppercase", "Version"),
                    th(cls := "w-12 px-3 py-2 text-center text-xs font-medium text-gray-500 uppercase", "Status"),
                    th(cls := "w-20 px-3 py-2 text-left text-xs font-medium text-gray-500 uppercase", "Reason"),
                    sortableHeader("Tested", "days", "w-20 text-right"),
                    sortableHeader("Scala Version", "scala", "min-w-56 text-left")
                  )
                ),
                tbody(
                  id := "projects-tbody",
                  cls := "bg-white divide-y divide-gray-200",
                  projectsList.projects.map(projectRow)
                )
              ),
              div(
                cls := "py-4 text-center text-gray-500 text-sm",
                s"Showing all ${projectsList.projects.length} projects"
              )
            ),
            // Client-side sorting script
            script(raw(projectsSortingScript))
          )
      )
    )

  /** Sortable table header */
  private def sortableHeader(label: String, sortKey: String, extraClasses: String): Frag =
    th(
      cls := s"$extraClasses px-3 py-2 text-xs font-medium text-gray-500 uppercase cursor-pointer hover:bg-gray-100 select-none",
      attr("data-sort") := sortKey,
      attr("onclick") := s"sortTable('$sortKey')",
      label,
      span(cls := "ml-1 text-gray-400 sort-indicator", "↕")
    )

  /** JavaScript for client-side table sorting */
  private val projectsSortingScript: String = """
    let currentSort = { key: null, asc: true };
    
    function sortTable(key) {
      const tbody = document.getElementById('projects-tbody');
      const rows = Array.from(tbody.querySelectorAll('tr'));
      
      // Toggle direction if same key
      if (currentSort.key === key) {
        currentSort.asc = !currentSort.asc;
      } else {
        currentSort.key = key;
        currentSort.asc = true;
      }
      
      // Column order: Project(0), Version(1), Status(2), Reason(3), Tested(4), Scala(5)
      rows.sort((a, b) => {
        let aVal, bVal;
        switch(key) {
          case 'name':
            aVal = a.cells[0].textContent.toLowerCase();
            bVal = b.cells[0].textContent.toLowerCase();
            break;
          case 'scala':
            aVal = a.cells[5].textContent.toLowerCase();
            bVal = b.cells[5].textContent.toLowerCase();
            break;
          case 'days':
            aVal = parseInt(a.getAttribute('data-days')) || 0;
            bVal = parseInt(b.getAttribute('data-days')) || 0;
            break;
          default:
            return 0;
        }
        
        if (aVal < bVal) return currentSort.asc ? -1 : 1;
        if (aVal > bVal) return currentSort.asc ? 1 : -1;
        return 0;
      });
      
      // Update indicators
      document.querySelectorAll('.sort-indicator').forEach(el => el.textContent = '↕');
      const header = document.querySelector(`th[data-sort="${key}"] .sort-indicator`);
      if (header) header.textContent = currentSort.asc ? '↑' : '↓';
      
      // Re-append sorted rows
      rows.forEach(row => tbody.appendChild(row));
    }
  """

  /** Single project row in the list */
  private def projectRow(project: ProjectSummary): Frag =
    val historyUrl = path(s"/projects/${project.projectName.org}/${project.projectName.repo}/history")
    val daysSinceTest = java.time.Duration.between(project.lastTested, java.time.Instant.now()).toDays

    // Color based on how stale the test is (check > 30 first, then > 7)
    val daysColor =
      if daysSinceTest > 30 then "text-red-600 font-medium"
      else if daysSinceTest > 7 then "text-orange-600"
      else "text-gray-500"

    // Truncate version to max 16 characters
    val truncatedVersion =
      if project.latestProjectVersion.length > 16 then project.latestProjectVersion.take(14) + "…"
      else project.latestProjectVersion

    tr(
      cls := "hover:bg-gray-50 cursor-pointer",
      attr("onclick") := s"window.location='$historyUrl'",
      attr("data-days") := daysSinceTest.toString, // For sorting
      // Project name
      td(
        cls := "px-3 py-2",
        a(
          href := historyUrl,
          cls := "text-blue-600 hover:underline font-medium text-sm",
          title := (project.projectName: String),
          project.projectName: String
        )
      ),
      // Project version (truncated to 16 chars)
      td(
        cls := "px-3 py-2 text-xs text-gray-600",
        title := project.latestProjectVersion,
        truncatedVersion
      ),
      // Status
      td(
        cls := "px-3 py-2 text-center",
        statusBadgeCompact(project.status)
      ),
      // Failure reasons
      td(
        cls := "px-3 py-2",
        if project.status == BuildStatus.Failure then failureReasonsCompact(project.failureReasons)
        else span(cls := "text-gray-300 text-xs", "-")
      ),
      // Days since last test
      td(
        cls := "px-3 py-2 text-right",
        span(
          cls := s"text-xs $daysColor",
          title := project.lastTested.toString,
          if daysSinceTest == 0 then "today" else s"${daysSinceTest}d ago"
        )
      ),
      // Scala version (last column, full width for long strings)
      td(
        cls := "px-3 py-2 text-xs text-gray-600 whitespace-nowrap",
        title := project.latestScalaVersion,
        project.latestScalaVersion
      )
    )

  /** Compact status badge */
  private def statusBadgeCompact(status: BuildStatus): Frag =
    status match
      case BuildStatus.Success => span(cls := "text-emerald-600 text-lg", "✓")
      case BuildStatus.Failure => span(cls := "text-red-600 text-lg", "✗")
      case BuildStatus.Started => span(cls := "text-amber-600 text-lg", "⋯")

  /** Compact failure reasons */
  private def failureReasonsCompact(reasons: List[FailureReason]): Frag =
    div(
      cls := "flex flex-wrap gap-0.5",
      reasons
        .take(2)
        .map: reason =>
          val (color, abbrev) = reason match
            case FailureReason.Compilation     => ("bg-red-500", "C")
            case FailureReason.TestCompilation => ("bg-red-400", "TC")
            case FailureReason.Tests           => ("bg-orange-500", "T")
            case FailureReason.Publish         => ("bg-yellow-500", "P")
            case FailureReason.Scaladoc        => ("bg-purple-500", "S")
            case FailureReason.Build           => ("bg-gray-500", "B")
            case FailureReason.Other           => ("bg-purple-500", "?")
          span(
            cls := s"inline-flex items-center justify-center w-5 h-5 rounded text-[10px] font-bold text-white $color",
            title := reason.toString,
            abbrev
          )
    )
