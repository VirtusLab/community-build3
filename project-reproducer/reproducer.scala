// Use Scala 3.0 until pretty stacktraces are fixed
//> using scala "3.0.2"
//> using lib "org.json4s::json4s-native:4.0.3"
//> using lib "com.lihaoyi::requests:0.7.0"
//> using lib "com.lihaoyi::os-lib:0.8.1"
//> using lib "io.get-coursier:coursier_2.13:2.1.0-M5"
//> using lib "com.goyeau::kubernetes-client:0.8.1"
//> using lib "org.slf4j:slf4j-simple:1.6.4"
//> using lib "com.github.scopt::scopt:4.0.1"

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.json4s.JsonDSL.*
import coursier.{parse => *, util => *, *}
import scala.concurrent.*
import scala.concurrent.duration.*
import java.io.File

import javax.net.ssl.SSLContext
import coursier.cache.*

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
import Config.MinikubeConfig

given Formats = DefaultFormats
given ExecutionContext = ExecutionContext.Implicits.global

class FailedProjectException(msg: String) extends RuntimeException(msg)

private val CBRepoName = "VirtusLab/community-build3"
val projectBuilderUrl = s"https://raw.githubusercontent.com/$CBRepoName/master/project-builder"
val communityBuildRepo = s"https://github.com/$CBRepoName.git"
val communityBuildDir = gitCheckout(communityBuildRepo, None)(os.temp.dir())
lazy val scriptsDir = communityBuildDir / "scripts"
lazy val projectBuilderDir = communityBuildDir / "project-builder"

case class Config(
    jobId: String,
    mode: Config.Mode = Config.Mode.Minikube,
    scalaVersionOverride: Option[String] = None,
    buildFailedModulesOnly: Boolean = false,
    buildUpstream: Boolean = true,
    ignoreFailedUpstream: Boolean = false,
    jenkinsEndpoint: String = "https://scala3.westeurope.cloudapp.azure.com",
    minikube: Config.MinikubeConfig = Config.MinikubeConfig()
):
  def jenkinsBuildProjectJob(jobId: String) = s"$jenkinsEndpoint/job/buildCommunityProject/$jobId"
  def jenkinsRunBuildJob(jobId: String) = s"$jenkinsEndpoint/job/runBuild/$jobId"

object Config:
  enum Mode:
    case Minikube, Local
  case class MinikubeConfig(
      keepCluster: Boolean = false,
      keepMavenRepository: Boolean = false,
      namespace: String = "scala3-community-build",
      k8sConfig: File = (os.home / ".kube" / "config").toIO
  )

  val parser = {
    import scopt.OParser
    val builder = OParser.builder[Config]
    import builder.*
    OParser.sequence(
      head("Scala 3 Community Build reproducer tool", "v0.0.4"),
      opt[String]("jobId")
        .required()
        .action((x, c) => c.copy(jobId = x))
        .text("Id of Jenkins 'buildCommunityProject' job to retry"),
      opt[String]("scalaVersion")
        .action((x, c) => c.copy(scalaVersionOverride = Some(x)))
        .text("Scala version that should be used instead of the version used in the target build"),
      opt[Unit]("failedTargetsOnly")
        .action((x, c) => c.copy(buildFailedModulesOnly = true))
        .text("Build only failed modules of target project"),
      opt[Unit]("ignoreFailedUpstream")
        .action((x, c) => c.copy(ignoreFailedUpstream = true))
        .text("Ignore build failures of upstream projects"),
      opt[Unit]("noBuildUpstream")
        .action((x, c) => c.copy(buildUpstream = false))
        .text("Build upstream projects of the target"),
      opt[String]("jenkinsEndpoint")
        .action((x, c) => c.copy(jenkinsEndpoint = x))
        .text("Url of Jenkins instance to be used to gather build info instead of the default one")
        .hidden(),
      // Minikube speciifc
      opt[Unit]("keepCluster")
        .action((_, c) => c.copy(minikube = c.minikube.copy(keepCluster = true)))
        .text("Should Minikube cluster be kept after finishing the build"),
      opt[File]("k8sConfig")
        .action((x, c) => c.copy(minikube = c.minikube.copy(k8sConfig = x)))
        .text("Path to kubernetes config file, defaults to ~/.kube/config"),
      // Modes
      opt[Unit]("locally")
        .action((_, c) => c.copy(mode = Mode.Local))
        .text("Run build locally without minikube cluster")
    )
  }

@main def reproduce(args: String*): Unit =
  import Config.*
  scopt.OParser
    .parse(Config.parser, args, Config(jobId = ""))
    .fold(()) { implicit config: Config =>
      given build: BuildInfo = BuildInfo.fetchFromJenkins()

      config.mode match {
        case Mode.Minikube => MinikubeReproducer().run()
        case Mode.Local    => LocalReproducer().run()
      }
    }

case class ProjectInfo(id: String, params: BuildParameters, summary: BuildSummary) {
  def projectName = params.projectName

  def allDependencies(using build: BuildInfo): Set[ProjectInfo] =
    val deps = params.upstreamProjects.flatMap { name =>
      build.projectsByName.get(name).orElse {
        // Can only happen if project was replayed - then upstream info would be empty
        println(s"Not found upstream project $name in build info, it would be ignored")
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
}
case class BuildInfo(projects: List[ProjectInfo]):
  lazy val projectsByName = projects.map(p => p.projectName -> p).toMap
  lazy val projectsById = projects.map(p => p.id -> p).toMap
  // Following values are the same for all the projects
  lazy val mavenRepositoryUrl = projectsById.head._2.params.mavenRepositoryUrl
  def scalaVersion(using config: Config) = config.scalaVersionOverride
    .getOrElse { projectsById.head._2.params.scalaVersion }

object BuildInfo:
  def fetchFromJenkins()(using config: Config): BuildInfo =
    val jobId = config.jobId
    println(s"Fetching build info from Jenkins based on project $jobId")
    val runId = {
      val r =
        requests.get(s"${config.jenkinsBuildProjectJob(jobId)}/api/json?tree=actions[causes[*]]")
      val json = parse(r.data.toString)
      for {
        JArray(ids) <- (json \ "actions" \ "causes" \ "upstreamBuild").toOption
        JInt(id) <- ids.headOption
      } yield id.toString
    }

    val runProjectIds = runId.fold {
      println("No upstream project defined")
      List(config.jobId)
    } { runId =>
      val r = requests.get(s"${config.jenkinsRunBuildJob(runId)}/consoleText")
      val StartedProject = raw"Starting building: buildCommunityProject #(\d+)".r
      new String(r.data.array).linesIterator
        .collect { case StartedProject(id) => id }
        .toList
        .sorted
    }

    val getProjectsInfo = Future
      .traverse(runProjectIds) { jobId =>
        Future(BuildParameters.fetchFromJenkins(jobId))
          .zip(Future(BuildSummary.fetchFromJenkins(jobId)))
          .map(ProjectInfo(jobId, _, _))
      }
      .map(BuildInfo(_))

    Await.result(getProjectsInfo, duration.Duration.Inf)

end BuildInfo

case class BuildSummary(projects: List[BuildProjectSummary]) {
  lazy val failedTargets = projects.collect {
    case BuildProjectSummary(organization, artifactName, _, results) if results.hasFailure =>
      s"$organization%$artifactName"
  }
}
object BuildSummary:
  def fetchFromJenkins(jobId: String)(using config: Config): BuildSummary =
    val r = requests.get(
      s"${config.jenkinsBuildProjectJob(jobId)}/artifact/build-summary.txt",
      check = false
    )
    val projects = if !r.is2xx then
      System.err.println(
        s"Failed to get build summary for job $jobId, assuming it failed in all submodules"
      )
      Nil
    else
      for
        JObject(projects) <- parse(r.data.toString)
        JField(artifactName, results: JObject) <- projects
        JString(projectName) <- results \ "meta" \ "projectName"
        JString(orgName) <- results \ "meta" \ "organization"
        BuildResult(compile) <- results \ "compile"
        BuildResult(testCompile) <- results \ "test-compile"
      yield BuildProjectSummary(
        organization = orgName,
        artifactName = artifactName,
        projectName = projectName,
        results = ProjectTargetResults(
          compile = compile,
          testCompile = testCompile
        )
      )
    BuildSummary(projects)

case class BuildProjectSummary(
    organization: String,
    artifactName: String, // Name of the created artifact
    projectName: String,  // Name of the project within the build
    results: ProjectTargetResults
)

// Ignore publish step
case class ProjectTargetResults(compile: BuildResult, testCompile: BuildResult) {
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

def gitCheckout(repoUrl: String, revision: Option[String])(cwd: os.Path): os.Path =
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

class BuildParameters(params: Map[String, String]):
  val projectName = params("projectName")
  val projectConfig = params.get("projectConfig").filter(_.nonEmpty)
  val projectReposiotryUrl = params("repoUrl")
  val projectRevision = params.get("revision").filter(_.nonEmpty)
  val projectVersion = params.get("version").filter(_.nonEmpty)

  val scalaVersion = params("scalaVersion")
  val jdkVersion = params.get("javaVersion").filter(_.nonEmpty)
  val enforcedSbtVersion = params.get("enforcedSbtVersion").filter(_.nonEmpty)

  val mavenRepositoryUrl = params("mvnRepoUrl")

  val buildTargets = params("targets").split(' ').toList
  val upstreamProjects = params("upstreamProjects").split(",").filter(_.nonEmpty).toList

object BuildParameters:
  def fetchFromJenkins(jobId: String)(using config: Config): BuildParameters =
    val jobApi = s"${config.jenkinsBuildProjectJob(jobId)}/api"
    val r =
      requests.get(s"$jobApi/json?tree=actions[parameters[*]]")
    val json = parse(r.data.toString)
    val params = for {
      JArray(params) <- json \ "actions" \ "parameters"
      JObject(param) <- params
      JField("name", JString(name)) <- param
      JField("value", JString(value)) <- param
    } yield name -> value
    BuildParameters(params.toMap)

private def checkRequiredApps(executables: String*): Unit =
  val isWindows = sys.props("os.name").toLowerCase.startsWith("windows")
  val which = if isWindows then "where" else "which"
  val missing = executables.filterNot { name =>
    val out = new String(os.proc(which, name).call(check = false).out.bytes)
    out.linesIterator.filter(_.nonEmpty).hasNext
  }.toList

  if missing.nonEmpty then
    System.err.println(
      "Required programs are not installed or installed or set on PATH: " + missing.mkString(" ")
    )
    sys.exit(1)

class MinikubeReproducer(using config: Config, build: BuildInfo):
  checkRequiredApps("minikube", "kubectl")
  def jobId = config.jobId
  import MinikubeReproducer.*

  private given k8s: MinikubeConfig = config.minikube
  private given IORuntime = IORuntime.global

  private val targetProject = build.projectsById(jobId)

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
                _ <- buildScalaCompilerIfMissing[IO](withLocalMaven(localMavenUrl))
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
      else println("Keeping minikube alive, run 'minikube delete' to delete minikube local cluster")

  private def startMinikube() =
    val isRunning = os
      .proc("minikube", "status", "--format={{.Host}}")
      .call()
      .out
      .text()
      .startsWith("Running")
    if !isRunning then bash("minikube", "start", s"--namespace=${k8s.namespace}")
    else println("Reusing existing minikube instance")

  private def setupCluster() =
    bash(
      "bash",
      "-c",
      s"kubectl create namespace ${k8s.namespace} --dry-run=client -o yaml | kubectl apply -f -"
    )
    val mavenIsRunning =
      os.proc("kubectl", "get", "deploy/mvn-repo", "--output=name")
        .call(check = false)
        .exitCode == 0
    if !mavenIsRunning then bash(scriptsDir / "start-mvn-repo.sh")

  private def buildScalaCompilerIfMissing[F[_]: Async: Logger: KubernetesClient](
      checkDeps: DependenciesChecker
  ): F[Unit] =
    for
      scalaReleaseExists <- Sync[F].blocking(checkDeps.scalaReleaseAvailable(build.scalaVersion))
      _ <-
        if !scalaReleaseExists then
          runJob(compilerBuilderJob, label = "Scala", canFail = false).void
        else Logger[F].info(s"Scala toolchain for version ${build.scalaVersion} already exists")
    yield ()

  private def buildProjectDependencies[F[_]: Async: Concurrent: Logger: KubernetesClient](
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
        if config.buildFailedModulesOnly && dependency.summary.failedTargets.isEmpty then
          log.info(s"$skipBaseMsg - no failed targets")
        // We use our own maven repo here, the base url from project.params is unique for each group of runs, has format of https://maven-repo:port/maven2/<build-data><seq_no>/
        else if depsCheck.projectReleased(dependency) then
          log.info(s"$skipBaseMsg - already build in previous run")
        else
          log.info(
            s"Starting to build dependency project $name"
          ) *> runJob(projectBuilderJob(using dependency), label = name, canFail = true).void
      }

    if !config.buildUpstream then log.info("Skipping building upstream projects")
    else
      for
        buildPlan <- Sync[F].blocking(targetProject.buildPlanForDependencies.zipWithIndex)
        _ <- buildPlan.traverse { (group, idx) =>
          val projectsInGroup = group.map(_.params.projectName)
          log.info(
            s"Starting projects build for group ${idx + 1}/${buildPlan.length} with projects: $projectsInGroup "
          ) *> buildDependenciesInGroup(group)
        }
      yield ()

  private def buildMainProject[F[_]: Async: Logger: KubernetesClient] =
    for {
      _ <- Logger[F].info(
        s"Starting build for target project $jobId (${targetProject.projectName})"
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

    def getContainerState = podsApi
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

    def waitForStart =
      for
        finalState <- getContainerState.iterateWhile { state =>
          state.waiting.flatMap(_.reason) match {
            case Some("ContainerCreating" | "ImagePullBackOff") => true
            case _                                              => false
          }
        }
        _ <-
          if finalState.running.isDefined then Sync[F].pure(())
          else
            Sync[F].raiseError(
              FailedProjectException(s"Failed to start pod of job ${jobName} ($label)")
            )
      yield ()

    def redirectLogsToFile = Sync[F].blocking {
      val logsFile = os.temp(
        prefix = s"cb-reproduce-log-$jobName",
        deleteOnExit = false
      )
      // workaround, using os.PathRedirect to file was not working
      Future {
        val logs = os
          .proc("kubectl", "-n", k8s.namespace, "logs", s"job/$jobName", "-f", "--timestamps=true")
          .spawn()
        val res = os.write.over(logsFile, logs.stdout)
      }
      logsFile
    }
    val projectRun =
      for
        _ <- performCleanup
        job <- jobsApi.createWithResource(jobDefninition)
        _ <- logger.info(s"Waiting for start of job $jobName ($label)")
        _ <- waitForStart
        _ <- logger.info(s"Pod for job $jobName ($label) started")

        logsFile <- redirectLogsToFile
        _ <- logger.info(
          s"Logs of job ${jobName} ($label) redirected to ${logsFile.toNIO.toAbsolutePath}"
        )

        exitCode <- getContainerState
          .iterateUntil(_.terminated.isDefined)
          .map(_.terminated.get.exitCode)
          .timeout(30.minute)
        _ <- logger.info(s"Job $jobName ($label) terminated with exit code $exitCode")

        _ <- Sync[F].whenA(exitCode != 0) {
          val errMsg = s"Build failed for job ${jobName} ($label)"
          if canFail && config.ignoreFailedUpstream then logger.error(errMsg)
          else Sync[F].raiseError(FailedProjectException(errMsg))
        }
      yield exitCode

    projectRun
      .onError(logger.error(_)(s"Failed to build project in job $jobName ($label)"))
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
  private def bash(args: os.Shellable*)(check: Boolean = true): os.CommandResult =
    os.proc(args)
      .call(
        check = check,
        stdout = os.Inherit,
        stderr = os.Inherit,
        env = Map("CB_K8S_NAMESPACE" -> k8s.namespace)
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
  private val imageVersion = "v0.0.4"

  def usingMavenServiceForwarder[T](fn: MavenForwarderPort ?=> T)(using k8s: MinikubeConfig): T =
    // Wait until mvn-repo is started
    os.proc(
      "kubectl",
      "wait",
      "pod",
      "--namespace=" + k8s.namespace,
      "--selector=app=mvn-repo",
      "--for=condition=Ready",
      "--timeout=1m"
    ).call()
    usingServiceForwarder("mvn-repo", 8081)(fn(using _))

  def projectBuilderJob(using project: ProjectInfo, k8s: MinikubeConfig, config: Config): Job =
    val params = project.params
    val effectiveTargets =
      if config.buildFailedModulesOnly then project.summary.failedTargets
      else params.buildTargets
    val effectiveScalaVersion = config.scalaVersionOverride
      .getOrElse(params.scalaVersion)
    Job(
      metadata = ObjectMeta(name = s"build-project-${project.id}", namespace = k8s.namespace),
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
                imageName = s"project-builder:jdk${params.jdkVersion.getOrElse("11")}-",
                args = Seq(
                  params.projectReposiotryUrl,
                  params.projectRevision.getOrElse(""),
                  effectiveScalaVersion,
                  params.projectVersion.getOrElse(""),
                  effectiveTargets.mkString(" "),
                  params.mavenRepositoryUrl,
                  params.enforcedSbtVersion.getOrElse(""),
                  params.projectConfig.getOrElse("{}")
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
                  "https://github.com/lampepfl/dotty.git",
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
      image = s"virtuslab/scala-community-build-$imageName$imageVersion",
      volumeMounts = Seq(
        VolumeMount(
          name = mvnRepoCrtSecret,
          mountPath = s"/usr/local/share/ca-certificates/$mvnRepoCrt",
          subPath = mvnRepoCrt,
          readOnly = true
        )
      ),
      // For some reason it seems to be broken
      lifecycle =
        Lifecycle(postStart = Handler(ExecAction(command = Seq("update-ca-certificates")))),
      command = Seq("/build/build-revision.sh"),
      args = args,
      tty = true,
      resources = ResourceRequirements(
        requests = Map("memory" -> Quantity("4Gi")),
        limits = Map("memory" -> Quantity("6Gi"))
      )
    )

  import javax.net.ssl.*
  import java.security.cert.X509Certificate
  object VerifiesAllHostNames extends HostnameVerifier {
    def verify(s: String, sslSession: SSLSession) = true
  }

  private lazy val unsafeSSLContext = {
    object TrustAll extends X509TrustManager {
      override def getAcceptedIssuers(): Array[X509Certificate] = Array()
      override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String) = ()
      override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String) = ()
    }

    val instance = SSLContext.getInstance("SSL")
    instance.init(null, Array(TrustAll), new java.security.SecureRandom())
    instance
  }

  def usingUnsafeSSLContext[T](fn: SSLContext ?=> T): T = {
    def withDefault[In, Res](set: In => Unit, newValue: In, oldValue: In)(block: => Res) = {
      set(newValue)
      try block
      finally set(oldValue)
    }
    withDefault(SSLContext.setDefault(_), unsafeSSLContext, SSLContext.getDefault) {
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

  private def usingServiceForwarder[T](serviceName: String, servicePort: Int)(fn: Int => T)(using
      k8s: MinikubeConfig
  ) =
    val service = s"service/$serviceName"
    val ForwardingLocallyOnPort = raw"Forwarding from 127.0.0.1:(\d+).*".r

    val forwarder =
      os.proc("kubectl", "-n", k8s.namespace, "port-forward", service, s":$servicePort")
        .spawn()
    try
      forwarder.stdout.buffered.readLine match {
        case ForwardingLocallyOnPort(port) =>
          Future(forwarder.stdout.buffered.lines.forEach(_ => ()))
          println(s"Forwarding $service on port ${port}")
          val res = fn(port.toInt)
          println("done")
          res
        case _ => sys.error(s"Failed to forward $service")
      }
    finally forwarder.destroy()
end MinikubeReproducer

class LocalReproducer(using config: Config, build: BuildInfo):
  checkRequiredApps("scala-cli", "mill", "sbt", "git", "scala")

  val effectiveScalaVersion = build.scalaVersion
  val targetProject = build.projectsById(config.jobId)
  private def effectiveTargets(using p: ProjectInfo) =
    if config.buildFailedModulesOnly then p.summary.failedTargets
    else p.params.buildTargets

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
      project.params.projectReposiotryUrl,
      project.params.projectRevision
    )(os.pwd)
    val logsFile = os.temp(prefix = s"cb-logs-build-project-${project.id}", deleteOnExit = false)
    val impl =
      if os.exists(projectDir / "build.sbt") then SbtReproducer(projectDir, logsFile)
      else if os.exists(projectDir / "build.sc") then MillReproducer(projectDir, logsFile)
      else sys.error("Unsupported build tool")
    try
      println(
        s"Starting build for project ${project.id} (${project.projectName}), logs redirected to $logsFile"
      )
      impl.prepareBuild()
      impl.runBuild()
    catch
      case ex: Exception if canFail =>
        System.err.println(s"Build for project ${project.id} (${project.projectName}) failed.")

  private def prepareScalaVersion(): Unit =
    val needsCompilation = !DependenciesChecker().scalaReleaseAvailable(effectiveScalaVersion)

    if !needsCompilation then println(s"Scala ${effectiveScalaVersion} toolchain already present")
    else
      val logsFile = os.temp("cb-build-compiler", deleteOnExit = false)
      println(
        s"Building Scala compiler for version $effectiveScalaVersion, logs redirected to $logsFile"
      )
      val VersionCommitSha = raw"3\..*-bin-([0-9a-f]*)-.*".r
      val revision = effectiveScalaVersion match {
        case VersionCommitSha(revision) => Some(revision)
        case _                          => None
      }
      val projectDir = gitCheckout("https://github.com/lampepfl/dotty.git", revision)(os.temp.dir())

      // Overwrite compiler baseVersion
      val buildFile = projectDir / "project" / "Build.scala"
      val updatedBuild =
        os.read(buildFile).replaceAll("(val baseVersion) = .*", s"$$1 = \"$effectiveScalaVersion\"")
      os.write.over(buildFile, updatedBuild)
      os
        .proc("sbt", "scala3-bootstrapped/publishLocal")
        .call(
          cwd = projectDir,
          env = Map("RELEASEBUILD" -> "yes"),
          stdout = logsFile,
          stderr = logsFile
        )
      println(s"Scala ${effectiveScalaVersion} was successfully published locally")
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

  class SbtReproducer(projectDir: os.Path, logsFile: os.Path)(using
      project: ProjectInfo,
      build: BuildInfo
  ) extends BuildToolReproducer:
    case class SbtConfig(options: List[String], commands: List[String])
    given Manifest[SbtConfig] = scala.reflect.Manifest.classType(classOf[SbtConfig])
    val CBPluginFile = "CommunityBuildPlugin.scala"
    val minSbtVersion = "1.5.5"
    val defaultSettings = Seq("-J-Xmx4G")
    val sbtConfig = project.params.projectConfig
      .map(parse(_) \ "sbt")
      .flatMap(_.extractOpt[SbtConfig])
      .getOrElse(SbtConfig(Nil, Nil))
    val sbtSettings = defaultSettings ++ sbtConfig.options
    val sbtBuildProperties = projectDir / "project" / "build.properties"

    // Assumes build.propeties contains only sbt.version
    val currentSbtVersion = os.read(sbtBuildProperties).trim.stripPrefix("sbt.version=")
    val belowMinimalSbtVersion =
      currentSbtVersion.split('.').take(3).map(_.takeWhile(_.isDigit).toInt) match {
        case Array(1, minor, patch) => minor < 5 || patch < 5
        case _                      => false
      }

    override def prepareBuild(): Unit =
      project.params.enforcedSbtVersion match {
        case Some(version) => os.write.over(sbtBuildProperties, s"sbt.version=$version")
        case _ =>
          if belowMinimalSbtVersion then
            println(
              s"Overwritting sbt version $currentSbtVersion with minimal supported version $minSbtVersion"
            )
            os.write.over(sbtBuildProperties, s"sbt.version=$minSbtVersion")
      }
      os.copy.into(
        projectBuilderDir / "sbt" / CBPluginFile,
        projectDir / "project",
        replaceExisting = true
      )

    override def runBuild(): Unit =
      try
        sbtClient(sbtSettings, "show crossScalaVersions")
        try sbtClient(s"++${effectiveScalaVersion}")
        catch case ex: Exception => sbtClient(s"++${effectiveScalaVersion}!")
        sbtClient("set every credentials := Nil")
        sbtClient("moduleMappings")
        if (sbtConfig.commands.nonEmpty) sbtClient(sbtConfig.commands)
        sbtClient("runBuild", effectiveTargets)
        println(s"Finished running build for ${project.id} (${project.projectName})")
      catch {
        case ex: Exception => System.err.println("Failed to run the build, check logs for details.")
      } finally sbtClient("shutdown")

    private def sbtClient(commands: os.Shellable*): os.CommandResult =
      os.proc("sbt", "--client", "--batch", "--no-colors", commands)
        .call(
          cwd = projectDir,
          stdout = os.PathAppendRedirect(logsFile),
          stderr = os.PathAppendRedirect(logsFile),
          env = Map("CB_MVN_REPO_URL" -> "")
        )

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
    val scalafixRulePath = "scalafix/rules/src/main/scala/fix/Scala3CommunityBuildMillAdapter.scala"
    val scalafixSettings = List(
      "--stdout",
      "--syntactic",
      "--scala-version=3.1.0"
    )
    override def prepareBuild(): Unit =
      val millBuilder = projectBuilderDir / "mill"
      val buildFile = projectDir / "build.sc"
      val buildFileCopy = projectDir / "build.scala"

      val scalafixClasspath = coursier
        .Fetch()
        .addDependencies(
          coursier.Dependency(
            Module(Organization("ch.epfl.scala"), ModuleName("scalafix-cli_2.13.8")),
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
      os.copy.into(millBuilder / MillCommunityBuildSc, projectDir, replaceExisting = true)

    override def runBuild(): Unit =
      def mill(commands: os.Shellable*) =
        os.proc("mill", millScalaSetting, commands)
          .call(
            cwd = projectDir,
            stdout = os.PathAppendRedirect(logsFile),
            stderr = os.PathAppendRedirect(logsFile)
          )
      val scalaVersion = Seq("--scalaVersion", effectiveScalaVersion)
      mill("runCommunityBuild", scalaVersion, effectiveTargets)
  end MillReproducer
end LocalReproducer

case class Dependency(org: String, name: String, version: String)
type RepositoriesMapping = Seq[Repository] => Seq[Repository]
object DependenciesChecker:
  private def isIvyRepo(repo: Repository) = repo.repr.startsWith("ivy:file:")
  val noLocalIvy: RepositoriesMapping = _.filterNot(isIvyRepo)
  val onlyLocalIvy: RepositoriesMapping = _.filter(isIvyRepo)

  def withLocalMaven(localMavenUrl: String)(using SSLContext) = DependenciesChecker(
    withRepositories = DependenciesChecker.noLocalIvy(_) :+ MavenRepository(localMavenUrl),
    fileCache = DependenciesChecker.unsafeFileCache
  )
  def onlyLocalMaven(localMavenUrl: String)(using SSLContext) = DependenciesChecker(
    withRepositories = _ => MavenRepository(localMavenUrl) :: Nil,
    fileCache = DependenciesChecker.unsafeFileCache
  )
  private def unsafeFileCache(using sslContext: SSLContext) = FileCache().noCredentials
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
      .mapResolutionParams(p => p)
  }

  private def checkDependenciesExist(dependencies: Seq[Dependency]) =
    import scala.util.Try
    import coursier.error.*
    // If dependency does not exists it would throw exception
    // By default coursier checks `.ivy2/local` (publishLocal target) and Maven repo
    val coursierDeps =
      for Dependency(org, name, version) <- dependencies
      yield coursier.Dependency(Module(Organization(org), ModuleName(name)), version)

    // Ignore missing transitive dependencies, they would be always missing in local maven repo
    def checkResoulationError(err: ResolutionError): Boolean =
      val expectedModules = coursierDeps.map(_.module)
      !err.errors.exists {
        case err: ResolutionError.CantDownloadModule => expectedModules.contains(err.module)
        case _ =>
          System.err.println(err)
          false
      }
    instance
      .withDependencies(coursierDeps)
      .either
      .fold(checkResoulationError, _ => true)

  def projectReleased(project: ProjectInfo): Boolean =
    project.params.projectVersion.fold {
      // If no version in the params (orignally was not published) we cannot determiate version
      false
    } { projectVersion =>
      val targetProjects =
        for
          target <- project.params.buildTargets
          Array(org, name) = target.split("%")
        yield Dependency(org, name + binarySuffix, projectVersion)
      val summaryProjects =
        for project <- project.summary.projects
        yield Dependency(project.organization, project.artifactName + binarySuffix, projectVersion)

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
