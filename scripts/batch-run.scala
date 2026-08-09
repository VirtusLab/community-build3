//> using scala 3.8
//> using jvm 25
//> using toolkit 0.9.2
//> using dep com.softwaremill.ox::core:1.0.6
//> using options -Wunused:all

/** Batch-run projects from `todo` via `scripts/run.sh`.
  *
  * Regenerates `.github/workflows/buildConfig.json` once for the whole list
  * (coordinator + `--offline-scaladex`), then runs each project with
  * `SKIP_BUILD_SETUP=1` so config resolution is not repeated. Builds run in
  * parallel under per-project workdirs.
  *
  * Outcomes:
  *   - success → append to `done.txt`
  *   - failure → append to `triage.txt`
  *
  * Usage (from community-build3 root):
  *   scala-cli run scripts/batch-run.scala --
  *   scala-cli run scripts/batch-run.scala -- --parallel=4 --limit=2
  *   scala-cli run scripts/batch-run.scala -- --resume
  */
import java.util.concurrent.atomic.AtomicInteger
import ox.*

final case class Args(
    parallel: Int = 4,
    todo: os.Path = os.pwd / "todo",
    done: os.Path = os.pwd / "done.txt",
    triage: os.Path = os.pwd / "triage.txt",
    workDir: os.Path = os.pwd / "batch-run-work",
    scalaVersion: Option[String] = None,
    limit: Option[Int] = None,
    only: Set[String] = Set.empty,
    resume: Boolean = false,
    executeTests: Boolean = true
)

object Args:
  def parse(args: Seq[String]): Args =
    args.foldLeft(Args()) {
      case (a, s"--parallel=$n") => a.copy(parallel = n.toInt)
      case (a, s"--todo=$p")     => a.copy(todo = os.Path(p, os.pwd))
      case (a, s"--done=$p")     => a.copy(done = os.Path(p, os.pwd))
      case (a, s"--triage=$p")   => a.copy(triage = os.Path(p, os.pwd))
      case (a, s"--work-dir=$p") => a.copy(workDir = os.Path(p, os.pwd))
      case (a, s"--scala=$v")    => a.copy(scalaVersion = Some(v))
      case (a, s"--limit=$n")    => a.copy(limit = Some(n.toInt))
      case (a, s"--only=$list") =>
        a.copy(only = list.split(",").map(_.trim).filter(_.nonEmpty).toSet)
      case (a, "--resume") => a.copy(resume = true)
      case (a, "--no-tests") => a.copy(executeTests = false)
      case (_, "--help" | "-h") =>
        println(help)
        sys.exit(0)
      case (_, unknown) =>
        System.err.println(s"Unknown arg: $unknown\n$help")
        sys.exit(2)
    }

  def help: String =
    """batch-run.scala
      |
      |  --parallel=N         max concurrent builds (default 4)
      |  --todo=PATH          project list (default ./todo)
      |  --done=PATH          successes file (default ./done.txt)
      |  --triage=PATH        failures file (default ./triage.txt)
      |  --work-dir=PATH      per-project run sandboxes (default ./batch-run-work)
      |  --scala=VERSION      Scala version for run.sh (default: last nightly)
      |  --limit=N            only first N remaining projects
      |  --only=org/repo,...  subset of projects
      |  --resume             skip projects already listed in done/triage
      |  --no-tests           set OPENCB_EXECUTE_TESTS=false
      |""".stripMargin

@main def main(args: String*): Unit =
  val parsed = Args.parse(args)
  val root = os.pwd
  require(os.exists(root / "scripts" / "run.sh"), "run from community-build3 root")
  require(os.exists(parsed.todo), s"missing todo file: ${parsed.todo}")

  val buildConfigPath = root / ".github" / "workflows" / "buildConfig.json"
  require(os.exists(buildConfigPath), s"missing $buildConfigPath")

  val already =
    if parsed.resume then loadProjectSet(parsed.done) ++ loadProjectSet(parsed.triage)
    else Set.empty[String]

  val selected =
    loadProjectsFile(parsed.todo)
      .filter(p => parsed.only.isEmpty || parsed.only.contains(p))
      .filterNot(already.contains)
      .pipe(ps => parsed.limit.fold(ps)(ps.take))

  println(s"Parallel:  ${parsed.parallel}")
  println(s"Todo:      ${parsed.todo} (${selected.size} to run, ${already.size} skipped)")
  println(s"Done:      ${parsed.done}")
  println(s"Triage:    ${parsed.triage}")
  println(s"Work dir:  ${parsed.workDir}")
  println(s"Tests:     ${parsed.executeTests}")

  if selected.isEmpty then
    println("Nothing to run")
    return

  val buildConfigBackup = parsed.workDir / "buildConfig.json.backup"
  os.makeDir.all(parsed.workDir)
  println(s"Backing up buildConfig.json -> $buildConfigBackup")
  os.copy.over(buildConfigPath, buildConfigBackup, createFolders = true)

  try
    println(s"Regenerating buildConfig.json for ${selected.size} projects...")
    regenerateBuildConfig(root, selected)
    ensureProjectsPresent(buildConfigPath, selected)

    val completed = AtomicInteger(0)
    val total = selected.size
    val resultsLock = new Object

    def progress(msg: String): Unit =
      println(s"[${completed.get()}/$total] $msg")

    def appendLine(path: os.Path, project: String): Unit =
      resultsLock.synchronized:
        os.write.append(path, project + "\n", createFolders = true)

    selected.mapPar(parsed.parallel): project =>
      progress(s"START $project")
      val ok =
        try runProject(root, parsed, project)
        catch
          case ex: Throwable =>
            progress(s"ERROR $project: ${ex.getMessage}")
            false
      completed.incrementAndGet()
      if ok then
        appendLine(parsed.done, project)
        progress(s"PASS  $project -> done")
      else
        appendLine(parsed.triage, project)
        progress(s"FAIL  $project -> triage")
      ok
  finally
    if os.exists(buildConfigBackup) then
      println(s"Restoring buildConfig.json from $buildConfigBackup")
      os.copy.over(buildConfigBackup, buildConfigPath)

  println()
  println(s"Done file:   ${parsed.done}")
  println(s"Triage file: ${parsed.triage}")

extension [A](a: A)
  def pipe[B](f: A => B): B = f(a)

def loadProjectsFile(path: os.Path): List[String] =
  require(os.isFile(path), s"expected a file, got directory: $path")
  os.read
    .lines(path)
    .map(_.trim)
    .filter(_.nonEmpty)
    .filterNot(_.startsWith("#"))
    .toList
    .distinct

def loadProjectSet(path: os.Path): Set[String] =
  if os.isFile(path) then loadProjectsFile(path).toSet
  else if os.isDir(path) then
    System.err.println(s"Warning: ignoring directory $path (use --done/--triage for result files)")
    Set.empty
  else Set.empty

/** One-shot coordinator run writing buildConfig.json for exactly these projects. */
def regenerateBuildConfig(root: os.Path, projects: List[String]): Unit =
  val n = projects.size
  val projectList = projects.mkString(",")
  val configs = root / "coordinator" / "configs"
  val logFile = root / "batch-run-work" / "last-coordinator-regen.log"
  os.makeDir.all(logFile / os.up)

  def q(s: String): String =
    "'" + s.replace("'", "'\"'\"'") + "'"

  // 3 = scala binary, 1 = min stars, n = max in config/plan (covers required list)
  val cmd =
    s"""set -e
       |scala-cli run ${q((root / "coordinator").toString)} -- \\
       |  3 1 ${n} ${n} ${q(projectList)} ${q(configs.toString)} \\
       |  --offline-scaladex \\
       |  > ${q(logFile.toString)} 2>&1
       |""".stripMargin

  val result = os.proc("bash", "-lc", cmd).call(cwd = root, check = false)
  if result.exitCode != 0 then
    System.err.println(s"Coordinator regeneration failed (exit=${result.exitCode}), see $logFile")
    sys.exit(result.exitCode)
  println(s"Coordinator regeneration OK (log: $logFile)")

def ensureProjectsPresent(buildConfigPath: os.Path, projects: List[String]): Unit =
  val rootJson = ujson.read(os.read(buildConfigPath))
  val missing = projects.filterNot(rootJson.obj.contains)
  if missing.nonEmpty then
    System.err.println(
      s"After regeneration, still missing (${missing.size}): ${missing.take(10).mkString(", ")}"
    )
    sys.exit(1)

def runProject(root: os.Path, args: Args, project: String): Boolean =
  val work = args.workDir / project.replace('/', '_')
  os.makeDir.all(work)

  var env = Map(
    "SKIP_BUILD_SETUP" -> "1",
    "OFFLINE_SCALADEX" -> "1",
    "OPENCB_EXECUTE_TESTS" -> args.executeTests.toString,
    "OPENCB_GIT_DEPTH" -> "1",
    "CI" -> "true"
  )
  val akkaToken = root / ".secrets" / "akka-repo-token"
  if os.exists(akkaToken) then
    val token = os.read(akkaToken).trim
    env = env ++ Map(
      "OPENCB_AKKA_REPO_TOKEN" -> token,
      "AKKA_IO_REPOSITORY_KEY" -> token
    )

  val runSh = (root / "scripts" / "run.sh").toString
  val cmd: Seq[os.Shellable] = args.scalaVersion match
    case Some(v) => Seq(runSh, project, v)
    case None    => Seq(runSh, project)

  val result =
    os.proc(cmd)
      .call(
        cwd = work,
        env = env,
        check = false,
        stdout = os.Inherit,
        stderr = os.Inherit
      )
  result.exitCode == 0
