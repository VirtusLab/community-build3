import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import scala.sys.process._

def pb(args: (String | Path | Option[String] | Seq[String])*) = 
  Process(flattenArgs(args:_*))

def flattenArgs(args: (String | Path | Option[String] | Seq[String])*) = 
  args.flatMap{
    case s: String => Seq(s)
    case p: Path => Seq(p.toString)
    case o: Option[String] => o.toSeq
    case s: Seq[String] => s
  }

def repo(step: BuildStep) = s"git@github.com:${step.p.org}/${step.p.name}.git"

val TagRef = """.+refs\/tags\/(.+)""".r

def findStepTag(step: BuildStep)(using l: ProcessLogger): Either[String, String] = 
  val cmd = Seq("git", "ls-remote", "--tags", repo(step).toString)
  util.Try {
    val lines = cmd.!!.linesIterator.filter(_.contains(step.originalVersion)).toList
    lines.collectFirst { case TagRef(tag) => tag } match
        case Some(tag) => Right(tag)
        case _ => Left(s"No tag in:\n${lines.map("-" + _ +"_").mkString("\n")}")
  }.toEither.left.map { e =>
    e.printStackTrace()
    e.getMessage
  }.flatten

def runSbt(
  step: BuildStep, 
  localScalaVersion: String,
  orgScalaVersion: String, 
  repoDir: Path): ProcessBuilder =
    val publishSettings = step.publishVersion.map("-Dcommunitybuild.version=" + _)
    val versionCmd = step.publishVersion.fold("")(v => 
      s""";set every version := "${v}" ;set every credentials := Nil """)
    // TODO We assume full crossbuild for now
    val targets = step.targets.map(_.asMvnStr.stripSuffix("_" + orgScalaVersion)) 
    // we want to store original modules names before overriding scala version
    val sbtCmdArg = s";moduleMappings ;++$localScalaVersion! $versionCmd ;runBuild ${targets.mkString(" ")}"
    val args = flattenArgs("sbt", "-sbt-version", "1.4.7",  publishSettings, sbtCmdArg)
    println("Will run: " + args.mkString(" "))
    Process(args, repoDir.toFile)

def setupSbt(repoDir: Path): ProcessBuilder =
  val pluginFileName = "CommunityBuildPlugin.scala"
  val orignalLoc =  Paths.get("resources").resolve(pluginFileName).toAbsolutePath
  val dest = repoDir.resolve("project").resolve(pluginFileName)
  pb("ln", "-s", orignalLoc, dest)

def setupOverrides(step: BuildStep, dest: Path): ProcessBuilder = 
  val overridesStrings = step.depOverrides.map(_.asMvnStr).mkString("\n")
  pb("echo", overridesStrings) #> dest.resolve("deps.txt").toFile

// TODO result from run
def buildProject(localScalaVersion: String, orgScalaVersion: String, step: BuildStep): Unit = 
  import step._
  val outDir = Paths.get("ws").resolve(p.name + "_" + step.originalVersion)
  val repoDir = outDir.resolve("repo")

   // prepare dir
  Seq("rm", "-rf", outDir.toString).!
  Seq("mkdir", outDir.toString).!
  
   val logs = outDir.resolve("logs.txt")

  val prefix = "[" + step.p.org + "/" + step.p.name + "@" + step.originalVersion + "]"
  def log(msg: String) = println(prefix + " " + msg)

  def run(name: String)(pb: ProcessBuilder)(using ProcessLogger): Either[String, Unit] =
    pb.!(summon[ProcessLogger]) match 
      case 0 => Right(())
      case code => Left(s"$name failed with $code using: $pb")


  
  val logger = ProcessLogger(logs.toFile)
  given ProcessLogger = logger
  
  try 
    val res = 
      for 
        tag <- findStepTag(step)
        _ = log(s"cloning $tag ...")
        _ <- run("cloning")(pb("git", "clone", repo(step), repoDir, "-b", tag, "--depth", "1"))
        _ <- run("install sbt pluggin")(setupSbt(repoDir))
        _ <- run("setup overrides")(setupOverrides(step, outDir))
        _ = log("building...")   
        _ <- run("running build")(runSbt(step, localScalaVersion, orgScalaVersion, repoDir))
      yield ()
    
    res match 
      case Left(msg) => log("ERROR: " + msg)
      case _ => log("completed")
  finally logger.close()

@main def runBuildPlan: Unit =
  val orgScalaVersion = "3.0.0-RC3"
  val deps = loadDepenenecyGraph(orgScalaVersion)
  val buildPlan: BuildPlan = makeStepsBasedBuildPlan(deps)

  val localScalaVersion = "3.0.0-RC3-bin-SNAPSHOT"
  for 
    steps <- buildPlan.steps.drop(1) // Dotty is build somewhere
    step <- steps  // if step.p.name == "processor"
  do buildProject(localScalaVersion,orgScalaVersion, step)
  