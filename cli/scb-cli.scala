#!/usr/bin/env -S scala-cli shebang
//> using scala "3.2"
//> using dep "org.json4s::json4s-native:4.0.6"
//> using dep "com.lihaoyi::requests:0.8.0"
//> using dep "com.lihaoyi::os-lib:0.9.1"
//> using dep "io.get-coursier:coursier_2.13:2.1.3"
//> using dep "com.goyeau::kubernetes-client:0.9.0"
//> using dep "org.slf4j:slf4j-simple:2.0.7"
//> using dep "com.github.scopt::scopt:4.1.0"

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.json4s.JsonDSL.*
import coursier.{parse => *, util => *, *}
import scala.concurrent.*
import scala.concurrent.duration.*
import java.io.File

import javax.net.ssl.SSLContext
import coursier.cache.*
import scala.util.control.NoStackTrace

import Config.*
import os.PathConvertible
import io.k8s.api.core.v1.ContainerState
given Formats = DefaultFormats
given ExecutionContext = ExecutionContext.Implicits.global

class FailedProjectException(msg: String)
    extends RuntimeException(msg)
    with NoStackTrace

val communityBuildVersion =
  sys.props.getOrElse("communitybuild.version", "0.3.20")
private val CBRepoName = "VirtusLab/community-build3"
val projectBuilderUrl =
  s"https://raw.githubusercontent.com/$CBRepoName/master/project-builder"
lazy val communityBuildLocalDir =
  sys.props.get("communitybuild.local.dir").map(os.Path(_))
lazy val communityBuildDir = communityBuildLocalDir
  .getOrElse(
    gitCheckout(s"https://github.com/$CBRepoName.git", None)(os.temp.dir())
  )
lazy val scriptsDir = communityBuildDir / "scripts"
lazy val projectBuilderDir = communityBuildDir / "project-builder"

case class Config(
    command: Config.Command = null,
    mode: Config.Mode = Config.Mode.Minikube,
    customRun: CustomBuildConfig = CustomBuildConfig(null, null),
    minikube: Config.MinikubeConfig = Config.MinikubeConfig(),
    redirectLogs: Boolean = true,
    publishArtifacts: Boolean = false,
    jobId: String = "custom",
    buildUpstream: Boolean = false,
    ignoreFailedUpstream: Boolean = false
):
  def withMinikube(fn: Config.MinikubeConfig => Config.MinikubeConfig) =
    copy(minikube = fn(minikube))
  def withCustomBuild(fn: CustomBuildConfig => CustomBuildConfig) =
    copy(customRun = fn(customRun))

case class CustomBuildConfig(
    projectName: String,
    scalaVersion: String,
    revisionOverride: Option[String] = None
)

object Config:
  enum Command:
    case RunCustomProject

  enum Mode:
    case Minikube, Local

  case class MinikubeConfig(
      keepCluster: Boolean = true,
      keepMavenRepository: Boolean = true,
      namespace: String = "scala3-community-build",
      k8sConfig: File = (os.home / ".kube" / "config").toIO
  )

  val parser = {
    import scopt.OParser
    val builder = OParser.builder[Config]
    import builder.*
    OParser.sequence(
      head("Scala 3 Community Build tool", communityBuildVersion),
      // Minikube specific
      opt[Unit]("removeCluster")
        .action { (_, c) => c.withMinikube(_.copy(keepCluster = false)) }
        .text("Should Minikube cluster be kept after finishing the build"),
      opt[Unit]("removeMavenRepo")
        .action { (_, c) =>
          c.withMinikube(_.copy(keepMavenRepository = false))
        }
        .text(
          "Should Maven repository instance should not be delete after finishing the build"
        ),
      opt[File]("k8sConfig")
        .action { (x, c) => c.withMinikube(_.copy(k8sConfig = x)) }
        .text("Path to kubernetes config file, defaults to ~/.kube/config"),
      opt[String]("namespace")
        .action { (x, c) => c.withMinikube(_.copy(namespace = x)) }
        .text("Custom minikube namespace to be used"),
      opt[Unit]("locally")
        .action { (_, c) => c.copy(mode = Mode.Local) }
        .text("Run build locally without minikube cluster"),
      opt[Unit]("noRedirectLogs")
        .action { (_, c) => c.copy(redirectLogs = false) }
        .text("Do not redirect runners logs to file")
        .hidden(),
      opt[Unit]("publishArtifacts")
        .action { (_, c) => c.copy(publishArtifacts = true) }
        .text("Publish artifacts of the build"),
      // Commands
      cmd("run")
        .action { (_, c) => c.copy(command = Config.Command.RunCustomProject) }
        .text("Run custom project using Community Build")
        .children(
          arg[String]("projectName")
            .text("Name of the Git repostiroy to run in format <org>/<repo>")
            .action { (x, c) => c.withCustomBuild(_.copy(projectName = x)) }
            .required(),
          arg[String]("scalaVersion")
            .text("Scala 3 version that should be used to run the build")
            .action { (x, c) => c.withCustomBuild(_.copy(scalaVersion = x)) }
            .required(),
          opt[String]("revision")
            .text("Name of repository tag or branch that should be used")
            .action { (x, c) =>
              c.withCustomBuild(_.copy(revisionOverride = Some(x)))
            }
        ),
      checkConfig { c =>
        if c.command == null then failure("Missing required command name")
        else success
      }
    )
  }

case class ProjectBuildPlan(
    project: String,
    dependencies: Array[String],
    repoUrl: String,
    revision: Option[String],
    version: Option[String],
    targets: String,
    config: Option[String]
)
object ProjectBuildPlan:
  given Manifest[ProjectBuildPlan] =
    scala.reflect.ManifestFactory.classType(classOf[ProjectBuildPlan])

@main def run(args: String*): Unit =
  import Config.*
  scopt.OParser
    .parse(Config.parser, args, Config())
    .fold(()) { implicit config: Config =>
      println("Gathering build info...")
      given BuildInfo = config.command match {
        case Command.RunCustomProject =>
          BuildInfo.forCustomProject(config, config.jobId)
      }
      config.mode match {
        case Mode.Minikube => MinikubeReproducer().run()
        case Mode.Local    => LocalReproducer().run()
      }
    }

case class ProjectInfo(
    id: String,
    params: BuildParameters,
    summary: BuildSummary
) {
  def projectName = params.name

  // Resolve organization name based on build targets, (<org>%<artifactId>)
  // Select the most popular entry (it is possible that targets might be polluted with 3rd party orgs)
  lazy val organization: String = params.buildTargets
    .flatMap(_.split('%').headOption)
    .groupBy(identity)
    .maxBy(_._2.length)
    ._1

  def allDependencies(using build: BuildInfo): Set[ProjectInfo] =
    val deps = params.upstreamProjects.flatMap { name =>
      build.projectsByName.get(name).orElse {
        // Can only happen if project was replayed - then upstream info would be empty
        println(
          s"Not found upstream project $name in build info, it would be ignored"
        )
        None
      }
    }.toSet
    val transtiveDeps = deps.flatMap(_.allDependencies)
    transtiveDeps ++ deps

  def buildPlanForDependencies(using BuildInfo): List[Set[ProjectInfo]] =
    @scala.annotation.tailrec
    def groupByDeps(
        remaining: Set[ProjectInfo],
        done: Set[String],
        acc: List[Set[ProjectInfo]]
    ): List[Set[ProjectInfo]] =
      if remaining.isEmpty then acc.reverse
      else
        val (newRemainings, currentStep) = remaining
          .partition(!_.params.upstreamProjects.forall(done.contains))
        val names = currentStep.map(_.projectName)
        groupByDeps(newRemainings, done ++ names, currentStep :: acc)
    end groupByDeps
    groupByDeps(allDependencies, Set.empty, Nil)
  end buildPlanForDependencies
    
  // Leave excluding project targets to the build tool internals
  def effectiveTargets(using config: Config) = params.buildTargets
}
case class BuildInfo(projects: List[ProjectInfo]):
  lazy val projectsByName = projects.map(p => p.projectName -> p).toMap
  lazy val projectsById = projects.map(p => p.id -> p).toMap
  // Following values are the same for all the projects
  lazy val mavenRepositoryUrl = projectsById.head._2.params.mavenRepositoryUrl
  def scalaVersion(using config: Config) = config.customRun.scalaVersion

object BuildInfo:
  def forCustomProject(config: Config, jobId: String): BuildInfo =
    val scalaVersion = config.customRun.scalaVersion
    given StringManifest: Manifest[String] =
      scala.reflect.ManifestFactory.classType(classOf[String])
    def prepareBuildPlan(): JValue =
      println(config.customRun.projectName)
      val args = Seq[os.Shellable](
        /* scalaBinaryVersion = */ 3,
        /* minStartsCount = */ 0,
        /* maxProjectsInConfig = */ 0,
        /* maxProjectsInBuildPlan = */ -1,
        /* requiredProjects = */ config.customRun.projectName,
        /* configsPath = */ communityBuildDir / "coordinator" / "configs"
      )
      val javaProps =
        Seq("--java-prop", "opencb.coordinator.reproducer-mode=true")
      val coordinatorDir = communityBuildDir / "coordinator"
      os.proc("scala-cli", "run", coordinatorDir, javaProps, "--", args)
        .call(cwd = coordinatorDir, stdout = os.Inherit)
      val buildPlanJson = os.read(coordinatorDir / "data" / "buildPlan.json")
      parse(buildPlanJson)

    val buildPlan = prepareBuildPlan() match
      case JArray(plan) => plan.filter(_ != JArray(Nil))
      case t            => sys.error("Unexpected build plan input: " + t)
    val projects = for
      case JArray(buildStage) <- buildPlan.take(1)
      // There should be only 1 stage
      project <- buildStage.take(1) // There should be only 1 project
      // Config is an object, though be default would be decoded to None when we expect Option[String]
      // We don't care about its content so we treat it as opaque string value
      configString = project \ "config" match {
        case JNothing | JNull => None
        case value            => Option(compact(render(value)))
      }
      plan = project.extract[ProjectBuildPlan].copy(config = configString)
      jdkVersion = sys.props
        .get("communitybuild.forced-java-version")
        .orElse(
          plan.config
            .map(parse(_) \ "java" \ "version")
            .flatMap(_.extractOpt[String])
        )
    yield ProjectInfo(
      id = jobId,
      params = BuildParameters(
        name = plan.project,
        config = plan.config.filter(_.nonEmpty),
        repositoryUrl = plan.repoUrl,
        repositoryRevision = config.customRun.revisionOverride
          .orElse(plan.revision)
          .filter(_.nonEmpty),
        version = plan.version.filter(_.nonEmpty),
        scalaVersion = scalaVersion,
        jdkVersion = jdkVersion,
        mavenRepositoryUrl =
          s"https://mvn-repo:8081/maven2/custom-${scalaVersion}",
        buildTargets = plan.targets.split(' ').toList,
        upstreamProjects = Nil
      ),
      summary = BuildSummary(Nil)
    )
    BuildInfo(projects)
  end forCustomProject

end BuildInfo

case class BuildSummary(projects: List[BuildProjectSummary]):
  lazy val failedArtifacts = projects.collect {
    case BuildProjectSummary(artifactName, results) if results.hasFailure =>
      artifactName
  }
  def failedTargets(project: ProjectInfo) =
    for artifact <- failedArtifacts
    yield s"${project.organization}%$artifact"

case class BuildProjectSummary(
    artifactName: String, // Name of the created artifact
    results: ProjectTargetResults
)

// Ignore publish step
case class ProjectTargetResults(
    compile: BuildResult,
    testCompile: BuildResult
) {
  def hasFailure = productIterator.contains(BuildResult.Failed)
}

enum BuildResult:
  case Ok, Skipped, Failed
object BuildResult:
  def unapply(value: JValue): Option[BuildResult] = value match {
    case JString("ok")     => Some(BuildResult.Ok)
    case JString("failed") => Some(BuildResult.Failed)
    case JNothing          => Some(BuildResult.Skipped)
    case _                 => None
  }

def gitCheckout(repoUrl: String, revision: Option[String])(
    cwd: os.Path
): os.Path =
  val repoDir = cwd
  println(s"Checkout $repoUrl")
  val projectDir = repoDir / "repo"
  os.remove.all(projectDir)
  val depth = revision.fold("--depth=1" :: Nil)(_ => Nil)
  os
    .proc("git", "clone", "--quiet", depth, repoUrl, projectDir.toString)
    .call(stderr = os.Pipe)
  revision.foreach { rev =>
    println(s"Setting project revision to $rev")
    os.proc("git", "checkout", rev).call(cwd = projectDir, stderr = os.Pipe)
  }
  projectDir

case class BuildParameters(
    name: String,
    config: Option[String],
    repositoryUrl: String,
    repositoryRevision: Option[String],
    version: Option[String],
    scalaVersion: String,
    jdkVersion: Option[String],
    mavenRepositoryUrl: String,
    buildTargets: List[String],
    upstreamProjects: List[String]
)

private def checkRequiredApps(executables: String*): Unit =
  val isWindows = sys.props("os.name").toLowerCase.startsWith("windows")
  val which = if isWindows then "where" else "which"
  val missing = executables.filterNot { name =>
    val out = new String(os.proc(which, name).call(check = false).out.bytes)
    out.linesIterator.filter(_.nonEmpty).hasNext
  }.toList

  if missing.nonEmpty then
    System.err.println(
      "Required programs are not installed or installed or set on PATH: " + missing
        .mkString(" ")
    )
    sys.exit(1)

class MinikubeReproducer(using config: Config, build: BuildInfo):
  import cats.Monad
  import cats.syntax.all.*
  import cats.effect.*
  import cats.effect.syntax.all.*
  import cats.effect.implicits.*
  import cats.effect.unsafe.IORuntime
  import org.typelevel.log4cats.Logger
  import org.typelevel.log4cats.slf4j.Slf4jLogger
  import com.goyeau.kubernetes.client.*
  import io.k8s.api.batch.v1.Job

  checkRequiredApps("minikube", "kubectl")
  import MinikubeReproducer.*

  private given k8s: MinikubeConfig = config.minikube
  private given IORuntime = IORuntime.global

  private val targetProject = build.projectsById(config.jobId)

  private def localMavenUrl(using port: MavenForwarderPort) = {
    build.mavenRepositoryUrl
      .replace(
        "https://mvn-repo:8081/maven2",
        s"https://localhost:$port/maven2/"
      )
  }

  def run(): Unit =
    startMinikube()
    try
      setupCluster()
      usingMavenServiceForwarder {
        usingUnsafeSSLContext {
          for {
            logger <- Slf4jLogger.create[IO]
            given Logger[IO] = logger
            _ <- KubernetesClient(
              KubeConfig.fromFile[IO](config.minikube.k8sConfig)
            ).use { implicit k8sCLient: KubernetesClient[IO] =>
              import DependenciesChecker.*
              for {
                _ <- logger.info("Starting")
                _ <- buildScalaCompilerIfMissing[IO](
                  withLocalMaven(localMavenUrl)
                )
                _ <- buildProjectDependencies[IO](onlyLocalMaven(localMavenUrl))
                _ <- buildMainProject[IO]
                _ <- logger.info("Build finished")
              } yield ()
            }
          } yield ()
        }.unsafeRunSync()
      }
    finally
      if !config.minikube.keepCluster then bash("minikube", "stop")
      else
        println(
          "Keeping minikube alive, run 'minikube delete' to delete minikube local cluster"
        )

  private def startMinikube() =
    val isRunning = os
      .proc("minikube", "status", "--format={{.Host}}")
      .call(check = false)
      .out
      .text()
      .startsWith("Running")
    if !isRunning then
      bash("minikube", "start", s"--namespace=${k8s.namespace}")
    else println("Reusing existing minikube instance")

  private def setupCluster() =
    bash(
      "bash",
      "-c",
      s"kubectl create namespace ${k8s.namespace} --dry-run=client -o yaml | kubectl apply -f -"
    )
    val mavenIsRunning =
      os.proc(
        "kubectl",
        "get",
        "deploy/mvn-repo",
        s"--namespace=${k8s.namespace}",
        "--output=name"
      ).call(check = false, stderr = os.Pipe)
        .exitCode == 0
    if !mavenIsRunning then bash(scriptsDir / "start-mvn-repo.sh")

  private def buildScalaCompilerIfMissing[F[
      _
  ]: Async: Logger: KubernetesClient](
      checkDeps: DependenciesChecker
  ): F[Unit] =
    val log = Logger[F]
    for
      scalaReleaseExists <- Sync[F].blocking(
        checkDeps.scalaReleaseAvailable(build.scalaVersion)
      )
      _ <-
        if !scalaReleaseExists then
          log.info(
            s"Scala toolchain for version ${build.scalaVersion} is missing"
          ) *>
            runJob(compilerBuilderJob, label = "Scala", canFail = false).void
        else
          log.info(
            s"Scala toolchain for version ${build.scalaVersion} already exists"
          )
    yield ()

  private def buildProjectDependencies[F[
      _
  ]: Async: Concurrent: Logger: KubernetesClient](
      depsCheck: DependenciesChecker
  )(using
      build: BuildInfo,
      config: Config
  ) =
    val log = Logger[F]
    def buildDependenciesInGroup(group: Set[ProjectInfo]) =
      group.toList.parTraverse { dependency =>
        val name = dependency.projectName
        def skipBaseMsg = s"Skip build for dependency $name"
        if dependency.summary
            .failedTargets(dependency)
            .isEmpty
        then log.info(s"$skipBaseMsg - no failed targets")
        // We use our own maven repo here, the base url from project.params is unique for each group of runs, has format of https://maven-repo:port/maven2/<build-data><seq_no>/
        else if depsCheck.projectReleased(dependency) then
          log.info(s"$skipBaseMsg - already build in previous run")
        else
          log.info(
            s"Starting to build dependency project $name"
          ) *> runJob(
            projectBuilderJob(using dependency),
            label = name,
            canFail = true
          ).void
      }

    if !config.buildUpstream then
      log.info("Skipping building upstream projects")
    else
      for
        buildPlan <- Sync[F].blocking(
          targetProject.buildPlanForDependencies.zipWithIndex
        )
        _ <- buildPlan.traverse { (group, idx) =>
          val projectsInGroup = group.map(_.params.name)
          log.info(
            s"Starting projects build for group ${idx + 1}/${buildPlan.length} with projects: $projectsInGroup "
          ) *> buildDependenciesInGroup(group)
        }
      yield ()

  private def buildMainProject[F[_]: Async: Logger: KubernetesClient] =
    for {
      _ <- Logger[F].info(
        s"Starting build for target project ${targetProject.projectName}"
      )
      _ <- runJob(
        projectBuilderJob(using targetProject),
        label = targetProject.projectName,
        canFail = false
      )
    } yield ()

  private def runJob[F[_]: Async: Logger](
      jobDefninition: Job,
      label: String,
      canFail: Boolean = false
  )(using
      k8sClient: KubernetesClient[F]
  ): F[Int] =
    val jobName = jobDefninition.metadata.flatMap(_.name).get
    val selectorLabels = Map("job-name" -> jobName)
    val jobsApi = k8sClient.jobs.namespace(k8s.namespace)
    val podsApi = k8sClient.pods.namespace(k8s.namespace)
    val logger = Logger[F]

    def performCleanup = jobsApi.delete(jobName) *>
      podsApi.deleteAll(selectorLabels) *>
      podsApi.list(selectorLabels).iterateUntil(_.items.isEmpty)

    def getContainerState(retries: Int = 60): F[ContainerState] = podsApi
      .list(selectorLabels)
      .delayBy(5.seconds)
      .map { pods =>
        for
          pod <- pods.items.headOption // There should be only 1 job
          status <- pod.status
          // There should be only 1 container
          containerStatus <- status.containerStatuses.flatMap(_.headOption)
          containerState <- containerStatus.state
        yield containerState
      }
      .iterateUntil(_.nonEmpty)
      .map(_.get)
      .recoverWith {
        case ex: Exception if retries > 0 =>
          Console.err.println(
            s"Failed to get container state, retry with backoff, reason: ${ex}"
          )
          Thread.sleep(1000)
          getContainerState(retries - 1)
      }

    def waitForStart =
      for
        finalState <- getContainerState().iterateWhile { state =>
          state.waiting.flatMap(_.reason) match {
            case Some("ContainerCreating" | "ImagePullBackOff") => true
            case _                                              => false
          }
        }
        _ <-
          if finalState.running.isDefined then Sync[F].pure(())
          else
            os.proc(
              "kubectl",
              "logs",
              s"job/$jobName",
              s"--namespace=${k8s.namespace}"
            ).call(stdout = os.Inherit, check = false)
            Sync[F].raiseError(
              FailedProjectException(
                s"Failed to start pod of job ${jobName} ($label)"
              )
            )
      yield ()

    def redirectLogs = Sync[F].delay {
      val logsFile = os.temp(
        prefix = s"cb-reproduce-log-$jobName",
        deleteOnExit = false
      )
      // workaround, using os.PathRedirect to file was not working
      def getLogs(): Future[Unit] = Future {
        val logs = os
          .proc(
            "kubectl",
            "-n",
            k8s.namespace,
            "logs",
            s"job/$jobName",
            "-f",
            "--timestamps=true"
          )
          .spawn(stdout = if config.redirectLogs then os.Pipe else os.Inherit)
        os.write.over(logsFile, logs.stdout)
      }.recoverWith { case ex: Exception =>
        Console.err.println(s"Error when streaming logs: ${ex}, would retry")
        Thread.sleep(1000)
        getLogs()
      }
      val stdout = getLogs()

      logsFile
    }

    val projectRun =
      for
        _ <- logger.info(s"Starting build for job $label")
        _ <- performCleanup
        _ <- logger.info(s"Creating new job $label")
        job <- jobsApi.createWithResource(jobDefninition)
        _ <- logger.info(s"Waiting for start of job $jobName ($label)")
        _ <- waitForStart
        _ <- logger.info(s"Pod for job $jobName ($label) started")

        logsFile <- redirectLogs
        _ <-
          if !config.redirectLogs then Sync[F].pure(())
          else
            logger.info(
              s"Logs of job ${jobName} ($label) redirected to ${logsFile.toNIO.toAbsolutePath}"
            )

        exitCode <- getContainerState()
          .iterateUntil(_.terminated.isDefined)
          .map(_.terminated.get.exitCode)
          .timeout(60.minute)
        _ <- logger.info(
          s"Job $jobName ($label) terminated with exit code $exitCode"
        )

        _ <- Sync[F].whenA(exitCode != 0) {
          val errMsg = s"Build failed for job ${jobName} ($label)"
          if canFail && config.ignoreFailedUpstream then logger.error(errMsg)
          else Sync[F].raiseError(FailedProjectException(errMsg))
        }
      yield exitCode

    projectRun
      .onError(
        logger.error(_)(s"Failed to build project in job $jobName ($label)")
      )
      .guarantee {
        performCleanup
          .onError(
            logger
              .error(_)(
                s"Failed to delete job, use 'kubectl delete $jobName' to purge the resource'"
              )
          )
          .void
      }
  end runJob

  private def bash(args: os.Shellable*): os.CommandResult = bash(args: _*)()
  private def bash(
      args: os.Shellable*
  )(check: Boolean = true): os.CommandResult =
    os.proc(args)
      .call(
        check = check,
        stdout = os.Inherit,
        stderr = if check then os.Inherit else os.Pipe,
        env = Map(
          "CB_K8S_NAMESPACE" -> k8s.namespace,
          "CB_VERSION" -> communityBuildVersion
        )
      )

object MinikubeReproducer:
  opaque type MavenForwarderPort = Int
  opaque type UnsafeSSLContext = javax.net.ssl.SSLContext

  import io.k8s.api.core.v1.*
  import io.k8s.api.batch.v1.*
  import io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta

  // Kubernetes API has A LOT of Options, simplify it locally
  private given toSome[T]: Conversion[T, Option[T]] = Some(_)
  import scala.language.implicitConversions

  private val mvnRepoCrtSecret = "mvn-repo-cert"
  private val mvnRepoCrt = "mvn-repo.crt"

  def usingMavenServiceForwarder[T](fn: MavenForwarderPort ?=> T)(using
      k8s: MinikubeConfig
  ): T =
    // Wait until mvn-repo is started
    def waitForPod() = os
      .proc(
        "kubectl",
        "wait",
        "pod",
        "--namespace=" + k8s.namespace,
        "--selector=app=mvn-repo",
        "--for=condition=Ready",
        "--timeout=1m"
      )
      .call(check = false, stderr = os.Pipe)
    println("Waiting for Maven repository to start...")
    while {
      val p = waitForPod()
      p.exitCode != 0 && p.err.text().contains("error: no matching resources")
    } do ()
    usingServiceForwarder("mvn-repo", 8081)(fn(using _))

  def projectBuilderJob(using
      project: ProjectInfo,
      buildInfo: BuildInfo,
      k8s: MinikubeConfig,
      config: Config
  ): Job =
    val params = project.params
    Job(
      metadata = ObjectMeta(
        name = s"build-project-${project.id}",
        namespace = k8s.namespace
      ),
      spec = JobSpec(
        template = PodTemplateSpec(
          metadata = ObjectMeta(
            name = s"project-builder-${project.id}",
            namespace = k8s.namespace
          ),
          spec = PodSpec(
            volumes = Seq(mvnRepoSecretVolume),
            containers = Seq(
              builderContainer(
                imageName =
                  s"project-builder:jdk${params.jdkVersion.getOrElse("11")}-",
                args = Seq(
                  params.repositoryUrl,
                  params.repositoryRevision.getOrElse(""),
                  buildInfo.scalaVersion,
                  project.effectiveTargets.mkString(" "),
                  params.mavenRepositoryUrl,
                  params.config.getOrElse("{}"),
                  /* extra-scalac-options    = */ "",
                  /* disabled-scalac-options = */ "",
                  /* extra-library-deps = */ ""
                )
              )
            ),
            restartPolicy = "Never"
          )
        ),
        backoffLimit = 0
      )
    )

  def compilerBuilderJob(using
      k8s: MinikubeConfig,
      build: BuildInfo,
      config: Config
  ): Job =
    Job(
      metadata = ObjectMeta(name = "build-compiler", namespace = k8s.namespace),
      spec = JobSpec(
        template = PodTemplateSpec(
          metadata = ObjectMeta(
            name = "compiler-builder",
            namespace = k8s.namespace
          ),
          spec = PodSpec(
            volumes = Seq(mvnRepoSecretVolume),
            containers = Seq(
              builderContainer(
                imageName = "compiler-builder:",
                args = Seq(
                  "https://github.com/scala/scala3.git",
                  "main",
                  build.scalaVersion,
                  build.mavenRepositoryUrl
                )
              )
            ),
            restartPolicy = "Never"
          )
        ),
        backoffLimit = 0
      )
    )

  private val mvnRepoSecretVolume = Volume(
    name = mvnRepoCrtSecret,
    configMap = ConfigMapVolumeSource(name = mvnRepoCrtSecret)
  )

  private def builderContainer(imageName: String, args: Seq[String]) =
    import io.k8s.apimachinery.pkg.api.resource.Quantity
    Container(
      name = "builder",
      image =
        s"virtuslab/scala-community-build-$imageName$communityBuildVersion",
      volumeMounts = Seq(
        VolumeMount(
          name = mvnRepoCrtSecret,
          mountPath = s"/usr/local/share/ca-certificates/$mvnRepoCrt",
          subPath = mvnRepoCrt,
          readOnly = true
        )
      ),
      lifecycle = Lifecycle(postStart =
        LifecycleHandler(ExecAction(command = Seq("update-ca-certificates")))
      ),
      command = Seq("/build/build-revision.sh"),
      args = args,
      tty = true
    )

  import javax.net.ssl.*
  import java.security.cert.X509Certificate
  object VerifiesAllHostNames extends HostnameVerifier {
    def verify(s: String, sslSession: SSLSession) = true
  }

  private lazy val unsafeSSLContext = {
    object TrustAll extends X509TrustManager {
      override def getAcceptedIssuers(): Array[X509Certificate] = Array()
      override def checkClientTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ) = ()
      override def checkServerTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ) = ()
    }

    val instance = SSLContext.getInstance("SSL")
    instance.init(null, Array(TrustAll), new java.security.SecureRandom())
    instance
  }

  def usingUnsafeSSLContext[T](fn: SSLContext ?=> T): T = {
    def withDefault[In, Res](set: In => Unit, newValue: In, oldValue: In)(
        block: => Res
    ) = {
      set(newValue)
      try block
      finally set(oldValue)
    }
    withDefault(
      SSLContext.setDefault(_),
      unsafeSSLContext,
      SSLContext.getDefault
    ) {
      withDefault(
        HttpsURLConnection.setDefaultSSLSocketFactory,
        unsafeSSLContext.getSocketFactory,
        HttpsURLConnection.getDefaultSSLSocketFactory
      ) {
        withDefault(
          HttpsURLConnection.setDefaultHostnameVerifier,
          VerifiesAllHostNames,
          HttpsURLConnection.getDefaultHostnameVerifier
        ) {
          fn(using unsafeSSLContext)
        }
      }
    }
  }

  private def usingServiceForwarder[T](serviceName: String, servicePort: Int)(
      fn: Int => T
  )(using
      k8s: MinikubeConfig
  ) =
    val service = s"service/$serviceName"
    val ForwardingLocallyOnPort = raw"Forwarding from 127.0.0.1:(\d+).*".r
    def startForwarder(): (os.SubProcess, Int) =
      val forwarder = os
        .proc(
          "kubectl",
          "-n",
          k8s.namespace,
          "port-forward",
          service,
          s":$servicePort"
        )
        .spawn(stderr = os.Pipe)
      forwarder.stdout.buffered.readLine match {
        case null =>
          Thread.sleep(1000)
          startForwarder()
        case ForwardingLocallyOnPort(port) => (forwarder, port.toInt)
        case out => sys.error(s"Failed to forward $service - $out")
      }

    val (forwarder, port) = startForwarder()
    try
      Future(forwarder.stdout.buffered.lines.forEach(_ => ()))
      println(s"Forwarding $service on port ${port}")
      fn(port)
    finally forwarder.destroy()
end MinikubeReproducer

class LocalReproducer(using config: Config, build: BuildInfo):
  checkRequiredApps("scala-cli", "mill", "sbt", "git", "scala")

  val effectiveScalaVersion = build.scalaVersion
  val targetProject = build.projectsById(config.jobId)

  def run(): Unit =
    prepareScalaVersion()
    buildUpstreamProjects()
    buildProject(targetProject)

  private def buildUpstreamProjects() =
    if !config.buildUpstream then println("Skipping building upstream projects")
    else
      val depsCheck = DependenciesChecker(DependenciesChecker.onlyLocalIvy)
      for
        group <- targetProject.buildPlanForDependencies
        dep <- group
      do
        if depsCheck.projectReleased(dep) then
          println(
            s"Skipping building project ${dep.id} (${dep.projectName}) - already built in the previous run"
          )
        else buildProject(dep, canFail = config.ignoreFailedUpstream)

  private def buildProject(project: ProjectInfo, canFail: Boolean = false) =
    println(s"Building project ${project.id} (${project.projectName})")
    given ProjectInfo = project
    val projectDir = gitCheckout(
      project.params.repositoryUrl,
      project.params.repositoryRevision
    )(os.pwd)
    val logsFile = os.temp(
      prefix = s"cb-logs-build-project-${project.id}",
      deleteOnExit = false
    )
    val impl =
      if os.exists(projectDir / "build.sc") then
        MillReproducer(projectDir, logsFile)
      else if os.exists(projectDir / "build.sbt") then
        SbtReproducer(projectDir, logsFile)
      else ScalaCliReproducer(projectDir, logsFile)
    try
      val redirectMessage =
        if config.redirectLogs then s", logs redirected to $logsFile" else ""
      println(
        s"Starting build for project ${project.id} (${project.projectName})$redirectMessage"
      )
      impl.prepareBuild()
      impl.runBuild()
    catch
      case ex: Exception if canFail =>
        System.err.println(
          s"Build for project ${project.id} (${project.projectName}) failed."
        )

  private def prepareScalaVersion(): Unit =
    val needsCompilation =
      !DependenciesChecker().scalaReleaseAvailable(effectiveScalaVersion)

    if !needsCompilation then
      println(s"Scala ${effectiveScalaVersion} toolchain already present")
    else
      println(
        s"Building Scala compiler for version $effectiveScalaVersion"
      )
      val logsOutput =
        if !config.redirectLogs then os.Inherit
        else
          val logsFile = os.temp("cb-build-compiler", deleteOnExit = false)
          println(s"Scala compiler build logs redirected to $logsFile")
          os.PathRedirect(logsFile)
      val VersionCommitSha = raw"3\..*-bin-([0-9a-f]*)-.*".r
      val revision = effectiveScalaVersion match {
        case VersionCommitSha(revision) => Some(revision)
        case _                          => None
      }
      val projectDir =
        gitCheckout("https://github.com/scala/scala3.git", revision)(
          os.temp.dir()
        )

      // Overwrite compiler baseVersion
      val buildFile = projectDir / "project" / "Build.scala"
      val updatedBuild =
        os.read(buildFile)
          .replaceAll(
            "(val baseVersion) = .*",
            s"$$1 = \"$effectiveScalaVersion\""
          )
      os.write.over(buildFile, updatedBuild)
      os
        .proc("sbt", "scala3-bootstrapped/publishLocal")
        .call(
          cwd = projectDir,
          env = Map("RELEASEBUILD" -> "yes"),
          stdout = logsOutput,
          stderr = logsOutput
        )
      println(
        s"Scala ${effectiveScalaVersion} was successfully published locally"
      )
  end prepareScalaVersion

  // Build reproducer impls
  abstract class BuildToolReproducer:
    def prepareBuild(): Unit
    def runBuild(): Unit
    def onFailure(v: BuildResult)(ret: => String): Option[String] =
      v match {
        case BuildResult.Failed => Some(ret)
        case _                  => None
      }

  class ScalaCliReproducer(projectDir: os.Path, logsFile: os.Path)(using
      project: ProjectInfo,
      build: BuildInfo
  ) extends BuildToolReproducer:
    override def prepareBuild(): Unit = ()
    override def runBuild(): Unit = {
        val logsOutput =
          if !config.redirectLogs then os.Inherit
          else os.PathAppendRedirect(logsFile)
        val effectiveConfig = project.params.config.getOrElse("{}")
        val projectbuilderDirOrURI = communityBuildLocalDir
          .map(_ / "project-builder")
          .getOrElse(projectBuilderUrl)
        os.proc(
          "scala-cli",
          "run",
          s"${projectbuilderDirOrURI}/scala-cli/build.scala",
          "--",
          projectDir,
          effectiveScalaVersion,
          effectiveConfig
        ).call(
          check = false,
          cwd = projectDir,
          stdout = logsOutput,
          stderr = logsOutput
        )
      }.exitCode match {
        case 0 =>
          println(
            s"Sucessfully finished build for project ${project.id} (${project.projectName})"
          )
        case code =>
          System.err.println(    s"Failed to run the build, for details check logs in $logsFile")
          throw FailedProjectException(
            s"Build for project ${project.id} (${project.projectName}) failed with exit code $code"
          )
    }

  class SbtReproducer(projectDir: os.Path, logsFile: os.Path)(using
      project: ProjectInfo,
      build: BuildInfo
  ) extends BuildToolReproducer:
    case class SbtConfig(options: List[String], commands: List[String])
    val CBPluginFile = "CommunityBuildPlugin.scala"
    val minSbtVersion = "1.5.5"
    val sbtConfig = project.params.config
      .map(parse(_) \ "sbt")
      .map { json =>
        // Parse manually, json4s seems to have some problems
        def stringList(field: String) = (json \ field) match {
          case JArray(fields) => fields.collect { case JString(value) => value }
          case _              => Nil
        }
        SbtConfig(stringList("options"), stringList("commands"))
      }
      .getOrElse(SbtConfig(Nil, Nil))
    val sbtSettings = sbtConfig.options
    val sbtBuildProperties = projectDir / "project" / "build.properties"
    val SbtVersion = raw"sbt\.version\s*=\s*(\d.*)".r
    val currentSbtVersion =
      if !os.exists(sbtBuildProperties) then None
      else
        os
          .read(sbtBuildProperties)
          .linesIterator
          .collectFirst { case SbtVersion(version) =>  version }
        // .getOrElse(sys.error("Cannot resolve current sbt version"))
    val belowMinimalSbtVersion =
      currentSbtVersion.forall {
        _.split('.').take(3).map(_.takeWhile(_.isDigit).toInt) match {
          case Array(1, minor, patch) => minor < 9 || (minor == 9 && patch < 0)
          case _                      => false
        }
      }

    override def prepareBuild(): Unit =
      val sbtProjectDir = sbtBuildProperties / os.up
      if !os.exists(sbtProjectDir) then os.makeDir(sbtProjectDir)
      if belowMinimalSbtVersion then
        println(
          s"Overwritting sbt version $currentSbtVersion with minimal supported version $minSbtVersion"
        )
        os.write.over(sbtBuildProperties, s"sbt.version=$minSbtVersion")
      os.copy.into(
        projectBuilderDir / "sbt" / CBPluginFile,
        projectDir / "project",
        replaceExisting = true
      )
      os.list(projectBuilderDir / "shared")
        .foreach(
          os.copy.into(_, projectDir / "project", replaceExisting = true)
        )
      os.proc("bash","-c", s"cd $sbtProjectDir && git submodule sync && git submodule update --init --recursive")
        .call(
          check = false,
          stdout = os.Inherit,
          stderr = os.Inherit
        ).exitCode

    override def runBuild(): Unit =
      def runSbt(forceScalaVersion: Boolean) =
        val logsOutput =
          if !config.redirectLogs then os.Inherit
          else os.PathAppendRedirect(logsFile)
        val versionSwitchSuffix = if forceScalaVersion then "!" else ""
        val tq = "\"" * 3
        val effectiveConfig = project.params.config.getOrElse("{}")
        os.proc(
          "sbt",
          "--no-colors",
          "--verbose",
          "set commands ++= CommunityBuildPlugin.commands",
          s"setCrossScalaVersions $effectiveScalaVersion",
          s"++$effectiveScalaVersion$versionSwitchSuffix -v",
          "set every credentials := Nil",
          "moduleMappings",
          "mapScalacOptions \"\" \"-deprecation,-feature,-Xfatal-warnings,-Werror\"",
          sbtConfig.commands,
          s"runBuild $effectiveScalaVersion $tq$effectiveConfig$tq ${project.effectiveTargets.mkString(" ")}"
        ).call(
          check = false,
          cwd = projectDir,
          stdout = logsOutput,
          stderr = logsOutput,
          env = Map("CB_MVN_REPO_URL" -> "")
        )

      def shouldRetryWithForcedScalaVerion = {
        val output = os.read(logsFile).toString
        def failedToSwitch = output.contains("Switch failed: no subproject")
        def missingMapping = output.contains("Module mapping missing:")
        failedToSwitch || missingMapping
      }

      def onSuccess = println(
        s"Sucessfully finished build for project ${project.id} (${project.projectName})"
      )
      def onFailure(code: Int) = {
        System.err.println(
          s"Failed to run the build, for details check logs in $logsFile"
        )
        throw FailedProjectException(
          s"Build for project ${project.id} (${project.projectName}) failed with exit code $code"
        )
      }

      runSbt(forceScalaVersion = false).exitCode match {
        case 0 => onSuccess
        case code if shouldRetryWithForcedScalaVerion =>
          println("Build failure, retrying with forced Scala version")
          runSbt(forceScalaVersion = true).exitCode match {
            case 0    => onSuccess
            case code => onFailure(code)
          }
        case code => onFailure(code)
      }

  end SbtReproducer

  class MillReproducer(projectDir: os.Path, logsFile: os.Path)(using
      project: ProjectInfo,
      build: BuildInfo
  ) extends BuildToolReproducer:
    val MillCommunityBuildSc = "MillCommunityBuild.sc"
    val millScalaSetting = List(
      "-D",
      s"communitybuild.scala=${effectiveScalaVersion}"
    )
    val scalafixRulePath =
      "scalafix/rules/src/main/scala/fix/Scala3CommunityBuildMillAdapter.scala"
    val scalafixSettings = List(
      "--stdout",
      "--syntactic",
      "--scala-version=3.1.0",
      "--settings.Scala3CommunityBuildMillAdapter.targetScalaVersion",
      effectiveScalaVersion
    )
    override def prepareBuild(): Unit =
      val millBuilder = projectBuilderDir / "mill"
      val buildFile = projectDir / "build.sc"
      val buildFileCopy = projectDir / "build.scala"

      val scalafixClasspath = coursier
        .Fetch()
        .addDependencies(
          coursier.Dependency(
            Module(
              Organization("ch.epfl.scala"),
              ModuleName("scalafix-cli_2.13.8")
            ),
            "0.9.34"
          )
        )
        .run
        .mkString(java.io.File.pathSeparator)

      os.copy.over(buildFile, buildFileCopy)
      os.proc(
        "java",
        "-cp",
        scalafixClasspath,
        millScalaSetting.mkString,
        "scalafix.v1.Main",
        s"--rules=file:${millBuilder}/$scalafixRulePath",
        s"--files=${buildFileCopy}",
        scalafixSettings
      ).call(cwd = projectDir, stdout = os.PathRedirect(buildFile))
      os.remove(buildFileCopy)
      os.copy.into(
        millBuilder / MillCommunityBuildSc,
        projectDir,
        replaceExisting = true
      )
      os.copy(
        millBuilder / "compat" / "0.11.sc",
        projectDir / "MillVersionCompat.sc",
        replaceExisting = true
      )
      val sharedSourcesDir = projectBuilderDir / "shared"
      os.list(sharedSourcesDir)
        .foreach { path =>
          // We need to rename .scala files into .sc to allow for their usage in Mill
          val relPath = path.relativeTo(sharedSourcesDir).toNIO
          val fileSc =
            relPath.getFileName().toString.stripSuffix(".scala") + ".sc"
          val outputPath =
            Option(relPath.getParent)
              .map(os.RelPath(_))
              .foldLeft(projectDir)(_ / _) / fileSc
          os.copy(path, outputPath, replaceExisting = true)
        }

    override def runBuild(): Unit =
      def mill(useLocal: Boolean, check: Boolean, commands: os.Shellable*) = {
        val output =
          if config.redirectLogs then os.PathAppendRedirect(logsFile)
          else os.Inherit
        os.proc(
          if useLocal then projectDir / "mill" else projectBuilderDir / "mill" / "millw",
          "--no-server",
          millScalaSetting,
          commands
        ).call(
          cwd = projectDir,
          stdout = output,
          stderr = output,
          check = check
        )
      }
      val millCmd: Seq[os.Shellable] = Seq(
        "runCommunityBuild",
        Seq("--scalaVersion", effectiveScalaVersion),
        project.params.config.getOrElse("{}"),
        project.effectiveTargets
      )
      if mill(useLocal = false, check = false, millCmd: _*).exitCode != 0 then
        mill(useLocal = true, check = true, millCmd: _*)

  end MillReproducer
end LocalReproducer

case class Dependency(org: String, name: String, version: String)
type RepositoriesMapping = Seq[Repository] => Seq[Repository]
object DependenciesChecker:
  private def isIvyRepo(repo: Repository) = repo.repr.startsWith("ivy:file:")
  val noLocalIvy: RepositoriesMapping = _.filterNot(isIvyRepo)
  val onlyLocalIvy: RepositoriesMapping = _.filter(isIvyRepo)

  def withLocalMaven(localMavenUrl: String)(using SSLContext) =
    DependenciesChecker(
      withRepositories =
        DependenciesChecker.noLocalIvy(_) :+ MavenRepository(localMavenUrl),
      fileCache = DependenciesChecker.unsafeFileCache
    )
  def onlyLocalMaven(localMavenUrl: String)(using SSLContext) =
    DependenciesChecker(
      withRepositories = _ => MavenRepository(localMavenUrl) :: Nil,
      fileCache = DependenciesChecker.unsafeFileCache
    )
  private def unsafeFileCache(using sslContext: SSLContext) =
    FileCache().noCredentials
      .withHostnameVerifier(MinikubeReproducer.VerifiesAllHostNames)
      .withSslSocketFactory(sslContext.getSocketFactory)

class DependenciesChecker(
    withRepositories: RepositoriesMapping = identity,
    fileCache: Cache[coursier.util.Task] = Cache.default
):
  val binarySuffix = "_3"
  val instance = {
    val default = coursier.Resolve()
    default
      .withCache(fileCache)
      .withRepositories(withRepositories(default.repositories))
  }

  private def checkDependenciesExist(dependencies: Seq[Dependency]) =
    println(s"Checking existance of dependencies: ${dependencies.toList}")
    import scala.util.Try
    import coursier.error.*
    // If dependency does not exists it would throw exception
    // By default coursier checks `.ivy2/local` (publishLocal target) and Maven repo
    val coursierDeps =
      for Dependency(org, name, version) <- dependencies
      yield coursier.Dependency(
        Module(Organization(org), ModuleName(name)),
        version
      )

    // Ignore missing transitive dependencies, they would be always missing in local maven repo
    def checkResoulationError(err: ResolutionError): Boolean =
      val expectedModules = coursierDeps.map(_.module)
      !err.errors.exists {
        case err: ResolutionError.CantDownloadModule =>
          expectedModules.contains(err.module)
        case _ =>
          System.err.println(err)
          false
      }
    val resolveF = instance
      .withDependencies(coursierDeps)
      .future
      .map(_ => true)
      .recover { case err: ResolutionError => checkResoulationError(err) }
    Await.result(resolveF, 1.minute)

  def projectReleased(project: ProjectInfo): Boolean =
    project.params.version.fold {
      // If no version in the params (orignally was not published) we cannot determiate version
      false
    } { projectVersion =>
      val targetProjects =
        for
          target <- project.params.buildTargets
          Array(org, name) = target.split("%")
        yield Dependency(org, name + binarySuffix, projectVersion)
      val organization = project.organization
      val summaryProjects =
        for project <- project.summary.projects
        yield Dependency(
          organization,
          project.artifactName + binarySuffix,
          projectVersion
        )

      val deps = (targetProjects ++ summaryProjects).distinct
      // If empty then we have not enough info -> always build
      if deps.isEmpty then false
      else checkDependenciesExist(deps)
    }

  def scalaReleaseAvailable(
      scalaVersion: String,
      extraRepositories: Seq[Repository] = Nil
  ): Boolean = checkDependenciesExist(
    for name <- Seq(
        "scala3-library",
        "scala3-compiler",
        "scala3-language-server",
        "scala3-staging",
        "scala3-tasty-inspector",
        "scaladoc",
        "tasty-core"
      ).map(_ + binarySuffix) ++ Seq("scala3-interfaces", "scala3-sbt-bridge")
    yield Dependency("org.scala-lang", name, scalaVersion)
  )
end DependenciesChecker
