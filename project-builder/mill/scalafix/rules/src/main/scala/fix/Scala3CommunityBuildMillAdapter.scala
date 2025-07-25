package fix

import scalafix.v1._
import scala.meta._
import metaconfig._

case class Scala3CommunityBuildMillAdapterConfig(
    targetScalaVersion: Option[String] = None,
    millBinaryVersion: Option[String] = None,
    isMainBuildFile: Option[Boolean] = None
)
object Scala3CommunityBuildMillAdapterConfig {
  def default = Scala3CommunityBuildMillAdapterConfig()
  implicit val surface: metaconfig.generic.Surface[fix.Scala3CommunityBuildMillAdapterConfig] =
    metaconfig.generic.deriveSurface[Scala3CommunityBuildMillAdapterConfig]
  implicit val decoder: metaconfig.ConfDecoder[fix.Scala3CommunityBuildMillAdapterConfig] =
    metaconfig.generic.deriveDecoder(default)
}

class Scala3CommunityBuildMillAdapter(
    config: Scala3CommunityBuildMillAdapterConfig
) extends SyntacticRule("Scala3CommunityBuildMillAdapter") {
  def this() = this(config = Scala3CommunityBuildMillAdapterConfig())
  def noInjects = sys.props.contains("communitybuild.noInjects")
  override def withConfiguration(config: Configuration): Configured[Rule] = {
    config.conf
      .getOrElse("Scala3CommunityBuildMillAdapter") {
        def propOrDefault(
            prop: String,
            default: Scala3CommunityBuildMillAdapterConfig => Option[String]
        ): Option[String] = sys.props
          .get(prop)
          .filter(_.nonEmpty)
          .orElse(default(this.config))

        this.config
          .copy(
            targetScalaVersion = propOrDefault("communitybuild.scala", _.targetScalaVersion),
            millBinaryVersion = propOrDefault(
              "communitybuild.millBinaryVersion",
              _.millBinaryVersion
            )
          )
      }
      .map(new Scala3CommunityBuildMillAdapter(_))
  }

  object isScala3Identifier {
    def apply(value: String): Boolean = scala3Identifiers.exists(value.equalsIgnoreCase)

    val scala3Identifiers = Seq(
      "Scala3",
      "ScalaDotty",
      "Scala3Version",
      "Scala_3",
      "Scala",
      "ScalaLts",
      "ScalaNext",
      "scalaVersion"
    )
  }

  val Scala3Literal = raw""""3.\d+.\d+(?:-RC\d+)?"""".r
  val millVersionSegments = config.millBinaryVersion.map(_.split('.').toList)
  def toIntOption(value: String) =
    try Some(value.toInt)
    catch { case ex: Throwable => None }
  val isMill0x = millVersionSegments.exists(_.headOption.contains("0"))
  val isMill1x = millVersionSegments.exists(_.headOption.contains("1"))

  val useLegacyMillCross = millVersionSegments.exists {
    case "0" :: minor :: _ => toIntOption(minor).exists(_ < 11)
    case _                 => false
  }
  val useLegacyTasks = millVersionSegments.exists {
    case "0" :: minor :: _ => toIntOption(minor).exists(_ < 12)
    case _                 => false
  }

  object Transform {
    val RootMill = Term.Select(Term.Name("_root_"), Term.Name("mill"))
    val TaskType = Term.Select(RootMill, if (useLegacyTasks) Term.Name("T") else Term.Name("Task"))
    val TaskCommandType = {
      if (isMill0x)
        Term.Select(TaskType, if (useLegacyTasks) Term.Name("command") else Term.Name("Command"))
      else
        Term.Apply(
          Term.Select(TaskType, Term.Name("Command")),
          Term.ArgClause(List(Term.Assign(Term.Name("exclusive"), Lit.Boolean(true))))
        )
    }

    def shouldWrapInTarget(body: Term, tpe: Option[Type]) = {
      val isLiteral = body.isInstanceOf[Lit.String]
      def hasTargetType = tpe match {
        case Some(Type.Apply(Type.Name("T" | "Task"), _)) => true
        case _                                            => false
      }
      !isLiteral || hasTargetType
    }

    def mapTraits(traits: List[Init]): List[Init] = {
      var coursierModuleInjected = false
      def traitOf(name: String) = Init(
        Type.Select(Term.Name("MillCommunityBuild"), Type.Name(name)),
        Name.Anonymous(),
        Nil
      )
      // format: off
      traits.map {
          case Init(name @ WithTypeName("CoursierModule"), _, _) =>
            coursierModuleInjected = true
            traitOf("CommunityBuildCoursierModule")
          case Init(name @ WithTypeName("PublishModule"), _, _) =>
            coursierModuleInjected = true
            traitOf("CommunityBuildPublishModule")
          case init @ Init(tpe @ Type.Apply(name @ WithTypeName("Cross"), List(tpeParam)), _, Seq(args)) =>
            def unconfigured = Term.Apply(
              Term.Select(Term.Name("sys"), Term.Name("exit")),
              List(Lit.String("targetScalaVersion not specified in scalafix config"))
            )
            if (useLegacyMillCross)
              init.copy(
                tpe = tpe.copy(tpe = Type.Name("MillCommunityBuildCross")),
                argss = List(args, List(config.targetScalaVersion.map(Lit.String(_)).getOrElse(unconfigured)))
              )
            else {
              init.copy(
                argss = List(
                  List(
                    Term.Apply(
                      Term.Select(Term.Name("MillCommunityBuild"), Term.Name("mapCrossVersions")),
                      config.targetScalaVersion.map(Lit.String(_)).getOrElse(unconfigured) :: args
                    )
                  )
                )
              )
            }
          case init => init
        } ++ {
          if (!coursierModuleInjected && anyTreeOfTypeName(
              has = coursierModuleSubtypes ++ testModuleSubtypes,
              butNot = List("CoursierModule", "PublishModule")
            )(traits)
          ) Seq(traitOf("CommunityBuildCoursierModule"))
          else Nil
        }
    }

    def injectScalacOptionsMapping = Seq(
      Defn.Def(Nil, Term.Name("scalacOptions"), Nil, Nil, None,
        body = Term.Apply(
          Term.Select(
            Term.Apply(
              Term.Select(Term.Super(Name.Anonymous(), Name.Anonymous()), Term.Name("scalacOptions")),
              List()
            ),
            Term.Name("mapScalacOptions")
          ),
          List(
            if(isMill1x)  Term.Apply(
              Term.Select(Term.This(Name.Anonymous()), Term.Name("scalaVersion")),
              List()
            ) else Term.Name("scalaVersion")
          )
        )
      )
    )

    def injectRootModuleRunCommand = {                  
      // def runCommunityBuild(_evaluator: _root_.mill.eval.Evaluator, scalaVersion: _root_.scala.Predef.String, projectDir: _root_.scala.Predef.String, configJson: _root_.scala.Predef.String, targets: _root_.scala.Predef.String*) = _root_.mill.T.command {
      //   implicit val ctx = MillCommunityBuild.Ctx(this, scalaVersion, _evaluator, _root_.mill.T.log)
      //   MillCommunityBuild.runBuild(configJson, projectDir, targets)
      // }""".stripMargin
      val stringType = Type.Select(Term.Select(Term.Select(Term.Name("_root_"), Term.Name("scala")), Term.Name("Predef")), Type.Name("String"))
      val evaluatorType = 
        if (isMill1x) 
          Type.Select(Term.Select(Term.Select(Term.Name("_root_"), Term.Name("mill")), Term.Name("api")), Type.Name("Evaluator"))
        else 
          Type.Select(Term.Select(Term.Select(Term.Name("_root_"), Term.Name("mill")), Term.Name("eval")), Type.Name("Evaluator"))
      Defn.Def(
        Nil, Term.Name("runCommunityBuild"), Nil, 
        paramss = List(
          List(
            Term.Param(Nil, Term.Name("evaluator"),    Some(evaluatorType), None),
            Term.Param(Nil, Term.Name("scalaVersion"), Some(stringType), None),
            Term.Param(Nil, Term.Name("configJson"),   Some(stringType), None),
            Term.Param(Nil, Term.Name("projectDir"),   Some(stringType), None),
            Term.Param(Nil, Term.Name("targets"),      Some(Type.Repeated(stringType)), None),
          )
        ),
        decltpe = None,
        body =
          Term.Apply(
            TaskCommandType,
            List(
              Term.Block(List(
                Defn.Def(
                  List(Mod.Implicit()),
                  Term.Name("ctx"), Nil, Nil, 
                  Some(Type.Select(Term.Name("MillCommunityBuild"), Type.Name("Ctx"))), 
                  Term.Apply(
                    Term.Select(Term.Name("MillCommunityBuild"), Term.Name("Ctx")),
                    List(
                      Term.This(Name.Anonymous()),
                      Term.Name("scalaVersion"),
                      Term.Name("evaluator"),
                      Term.Select(Term.Select(Term.Select(Term.Name("_root_"), Term.Name("mill")), Term.Name(if(useLegacyTasks) "T" else "Task")), Term.Name("log"))
                    )
                  )
                ),
                Term.Apply(
                  Term.Select(Term.Name("MillCommunityBuild"), Term.Name("runBuild")),
                  List(Term.Name("configJson"), Term.Name("projectDir"), Term.Name("targets"))
                )
            ))
          )
        )
      )
    }
    // format: on

    def isRootModule(defn: Tree): Boolean = config.isMainBuildFile.forall(_ == true) && {
      defn match {
        case defn: Defn.Object if isMill1x => defn.name.value == "package"
        case _ =>
          val template = defn match {
            case defn: Defn.Class  => defn.templ
            case defn: Defn.Trait  => defn.templ
            case defn: Defn.Object => defn.templ
            case _                 => return false
          }
          val Template(_, traits, _, stats) = template
          traits.exists {
            case Init(WithTypeName("RootModule" | "MillBuildRootModule"), _, _) => true
            case _                                                              => false
          }
      }
    }
    def transformDefn(defn: Tree): Tree = defn match {
      case defn @ (_: Defn.Class | _: Defn.Trait | _: Defn.Object) =>
        val template = defn match {
          case defn: Defn.Class  => defn.templ
          case defn: Defn.Trait  => defn.templ
          case defn: Defn.Object => defn.templ
        }
        val Template(_, traits, _, stats) = template
        val hasScalacOptions = stats.exists {
          case ValOrDefDef(Term.Name("scalacOptions"), _, _) => true
          case _                                             => false
        }

        val canHaveScalacOptions = anyTreeOfTypeName(has = ScalaModuleSubtypes)(traits)
        val updatedTemplate = template.copy(
          inits = mapTraits(traits),
          stats = stats.map(transformDefn).collect { case stat: Stat => stat } ++ {
            if (hasScalacOptions || !canHaveScalacOptions || noInjects) Nil
            else injectScalacOptionsMapping
          } ++ {
            if (isRootModule(defn) && !noInjects) {
              injectsRunCommandInRootModule = true
              Some(injectRootModuleRunCommand)
            } else None
          }
        )

        transformed += defn
        defn match {
          case defn: Defn.Class  => defn.copy(templ = updatedTemplate)
          case defn: Defn.Trait  => defn.copy(templ = updatedTemplate)
          case defn: Defn.Object => defn.copy(templ = updatedTemplate)
        }

      case tree @ ValOrDefDef(Term.Name("scalacOptions"), _, body) =>
        val updatedBody =
          Term.Apply(
            Term.Select(body, Term.Name("mapScalacOptions")),
            List(Term.Name("scalaVersion"))
          )
        tree match {
          case defn: Defn.Val => defn.copy(rhs = updatedBody)
          case defn: Defn.Def => defn.copy(body = updatedBody)
        }
      case tree @ ValOrDefDef(Term.Name("scalaVersion"), tpe, body) =>
        def replacement = {
          val updatedBody = config.targetScalaVersion
            .map(Lit.String(_))
            .map { v =>
              if (shouldWrapInTarget(body, tpe))
                if (isMill1x)
                  Term.Apply(Term.Select(Term.Name("mill"), Term.Name("Task")), List(v))
                else 
                  Term.Apply(Term.Select(Term.Name("mill"), Term.Name("T")), List(v))
              else v
            }
            .getOrElse(body)
          tree match {
            case defn: Defn.Val => defn.copy(rhs = updatedBody)
            case defn: Defn.Def => defn.copy(body = updatedBody)
          }
        }

        body.toString().trim() match {
          case Scala3Literal()                                           => replacement
          case id if id.split('.').indexWhere(isScala3Identifier(_)) > 0 => replacement
          case _                                                         => tree
        }

      case tree @ ValOrDefDef(Term.Name(id), tpe, body) if isScala3Identifier(id) =>
        body.toString().trim() match {
          case Scala3Literal() =>
            val updatedBody = config.targetScalaVersion
              .map(Lit.String(_))
              .map { v =>
                if (shouldWrapInTarget(body, tpe))
                  Term.Apply(Term.Select(Term.Name("mill"), Term.Name("T")), List(v))
                else v
              }
              .getOrElse(body)
            tree match {
              case defn: Defn.Val => defn.copy(rhs = updatedBody)
              case defn: Defn.Def => defn.copy(body = updatedBody)
            }
          case _ => tree
        }
      case _ =>
        defn
    }
  }

  var transformed = collection.mutable.Set.empty[Tree]
  var injectsRunCommandInRootModule = false
  override def fix(implicit doc: SyntacticDocument): Patch = {
    injectsRunCommandInRootModule = false
    lazy val headerInject = {
      if (noInjects) Patch.empty
      else {
        val insertAfter = doc.tree.collect {
          case tree: Import if Seq("$file", "$ivy").exists(tree.syntax.contains) => tree
          case pkg: Pkg                                                          => pkg
        }.lastOption
        val shouldInjectRunCommand = !injectsRunCommandInRootModule
        insertAfter match {
          case Some(Pkg(_, firstStat :: _)) =>
            Patch.addLeft(firstStat, Replacment.injects(shouldInjectRunCommand))
          case Some(insertAfter) =>
            Patch.addRight(insertAfter, Replacment.injects(shouldInjectRunCommand))
          case None =>
            // We want to add patches before first tree but after the comments
            Patch.addLeft(
              doc.tree.children.headOption.getOrElse(doc.tree),
              Replacment.injects(shouldInjectRunCommand)
            )
        }
      }
    }

    val patch = doc.tree.collect { case defn @ (_: Defn.Class | _: Defn.Trait | _: Defn.Object) =>
      lazy val maybeTransformed = Transform.transformDefn(defn)
      if (transformed.contains(defn) || maybeTransformed == defn) Patch.empty
      else
        Patch.replaceTree(
          defn, {
            if (sys.props.contains("communitybuild.noInjects")) maybeTransformed.syntax
            else
              replaceLast(maybeTransformed.syntax, "}") {
                if (Transform.isRootModule(defn))
                  "\n\t" + Replacment.MapScalacOptionsOps + "\n}"
                else "}"
              } + "\n"
          }
        )
    }.asPatch

    headerInject + patch
  }

  def replaceLast(input: String, substring: String)(replacement: String) = {
    val idx = input.lastIndexOf(substring)
    if (idx >= 0)
      input.substring(0, idx) + replacement + input.substring(idx + 1)
    else input
  }

  // format: off
  val ScalaModuleSubtypes = Seq(
    /* mill.scalajslib. */ "ScalaJSModule",
    /* mill.scalajslib.ScalaJSModule. */ "ScalaJSTests",
    /* mill.scalajslib. */ "TestScalaJSModule",
    /* mill.scalalib. */ "CrossModuleBase",
    /* mill.scalalib. */ "CrossSbtModule",
    /* mill.scalalib.CrossSbtModule. */ "Tests",
    /* mill.scalalib. */ "CrossScalaModule",
    /* mill.scalalib. */ "CrossScalaVersionRanges",
    /* mill.scalalib. */ "PlatformScalaModule",
    /* mill.scalalib. */ "SbtModule",
    /* mill.scalalib. */ "ScalaModule",
    /* mill.scalalib.ScalaModule. */ "ScalaTests",
    /* mill.scalalib. */ "UnidocModule",
    /* mill.scalanativelib. */ "SbtNativeModule",
    /* mill.scalanativelib. */ "ScalaNativeModule",
    /* mill.scalanativelib.ScalaNativeModule. */ "ScalaNativeTests",
    /* mill.scalanativelib. */ "TestScalaNativeModule",
    /* mill.scalajslib.ScalaJSModule. */ "Tests",
    /* mill.scalalib.CrossSbtModule. */ "CrossSbtModuleTests",
    /* mill.scalalib.SbtModule. */ "SbtModuleTests",
    /* mill.scalalib.bsp. */ "ScalaMetalsSupport"
  ).distinct
  val coursierModuleSubtypes = Seq(
    "CrossModuleBase","CrossSbtModule","CrossSbtModuleTests","CrossScalaModule","CrossScalaVersionRanges",
    "Giter8Module","Giter8Module",
    "JavaModule","JavaModuleTests",
    "MavenModule","MavenModuleTests",
    "PlatformScalaModule","PublishModule",
    "SbtModule","SbtModuleTests","SbtNativeModule","ScalaJSModule","ScalaJSTests","ScalaMetalsSupport","ScalaModule","ScalaNativeModule","ScalaNativeTests","ScalaTests","ScalafmtModule","ScalafmtModule","SemanticDbJavaModule",
    "TestScalaJSModule","TestScalaNativeModule","Tests",
    "UnidocModule",
    "ZincWorkerModule"
  )
  val testModuleSubtypes = Seq(
    "CrossSbtModuleTests",
    "JavaModuleTests","Junit4","Junit5",
    "MavenModuleTests",//"Munit",
    "SbtModuleTests","ScalaJSTests","ScalaNativeTests","ScalaTest","ScalaTests","Specs2",
    "TestNg","TestScalaJSModule","TestScalaNativeModule","Tests",
    "Utest",
    "Weaver",
    // "ZioTest",
  )
  // format: on
  object Replacment {
    def ScalaVersion(default: => String, asTarget: Boolean = true) =
      config.targetScalaVersion
        .map(quoted(_))
        .map(v => if (asTarget) s"mill.T($v)" else v)
        .getOrElse(default)

    private def quoted(v: String): String = {
      // Make sure that literal is quoted
      val quote = "\""
      val stripQutoes = v.stripPrefix(quote).stripSuffix(quote)
      quote + stripQutoes + quote
    }

    val MillCommunityBuildInject = {
      if (isMill1x)
        s"""
        |// Main entry point for community build
        |def runCommunityBuild(
        |  _evaluator: _root_.mill.api.Evaluator,
        |  scalaVersion: _root_.scala.Predef.String, 
        |  configJson: _root_.scala.Predef.String, 
        |  projectDir: _root_.scala.Predef.String, 
        |  targets: _root_.scala.Predef.String*) = mill.api.Task.Command(exclusive = true) {
        |  given MillCommunityBuild.Ctx = MillCommunityBuild.Ctx(this, scalaVersion, _evaluator, _root_.mill.Task.log)
        |  MillCommunityBuild.runBuild(configJson, projectDir, targets)
        |}
        |""".stripMargin
      else
        s"""
        |// Main entry point for community build
        |def runCommunityBuild(
        |   _evaluator: _root_.mill.eval.Evaluator, 
        |   scalaVersion: _root_.scala.Predef.String, 
        |   configJson: _root_.scala.Predef.String, 
        |   projectDir: _root_.scala.Predef.String, 
        |   targets: _root_.scala.Predef.String*) = ${Transform.TaskCommandType.toString()} {
        |  implicit val ctx = MillCommunityBuild.Ctx(this, scalaVersion, _evaluator, _root_.mill.T.log)
        |  MillCommunityBuild.runBuild(configJson, projectDir, targets)
        |}
        |""".stripMargin
    }
    val MillCommunityBuildCrossInject = {
      """
      |// Replaces mill.define.Cross allowing to use map used cross versions
      |class MillCommunityBuildCross[T: _root_.scala.reflect.ClassTag]
      |  (cases: _root_.scala.Any*)
      |  (buildScalaVersion: _root_.java.lang.String)
      |  (implicit ci:  _root_.mill.define.Cross.Factory[T], ctx: _root_.mill.define.Ctx) 
      |  extends _root_.mill.define.Cross[T](
      |      MillCommunityBuild.mapCrossVersionsAny(buildScalaVersion, cases): _*
      |    )
      |""".stripMargin
    }
    val MapScalacOptionsOps = {
      if (isMill0x) s"""|
      |implicit class MillCommunityBuildScalacOptionsOps(asSeq: Seq[String]){
      |  def mapScalacOptions(scalaVersion: mill.define.Target[String])(implicit ctx: mill.api.Ctx): Seq[String] = 
      |    _root_.scala.util.Try{ scalaVersion.evaluate(ctx).asSuccess.map(_.value) }
      |     .toOption.flatten
      |     .map(MillCommunityBuild.mapScalacOptions(_, asSeq))
      |     .getOrElse {
      |        println("Failed to resolve scalaVersion, assume it's Scala 3 project")
      |        MillCommunityBuild.mapScalacOptions(sys.props.getOrElse("communitybuild.scala", "3.3.1"), asSeq)
      |     }
      |  def mapScalacOptions(scalaVersion: String) = MillCommunityBuild.mapScalacOptions(scalaVersion, asSeq)
      |}
      |
      |implicit class MillCommunityBuildScalacOptionsTargetOps(asTarget: mill.define.Target[Seq[String]]){
      |  def mapScalacOptions(scalaVersion: mill.define.Target[String]) = scalaVersion.zip(asTarget).map {
      |    case (scalaVersion, scalacOptions) => MillCommunityBuild.mapScalacOptions(scalaVersion, scalacOptions)
      |  }
      |}
      |implicit class MillCommunityBuildTaskOps(asTarget: MillCompatTask[Seq[String]]){
      |  def mapScalacOptions(scalaVersion: MillCompatTask[String]) = scalaVersion.zip(asTarget).map {
      |    case (scalaVersion, scalacOptions) => MillCommunityBuild.mapScalacOptions(scalaVersion, scalacOptions)
      |  }
      |}
      |""".stripMargin
      else s"""
      |extension (value: Seq[String]) {
      |  def mapScalacOptions(scalaVersion: mill.api.Task[String])(using ctx: mill.api.TaskCtx): Seq[String] = 
      |    _root_.scala.util.Try{ scalaVersion.evaluate(ctx).toOption }
      |    .toOption.flatten
      |    .map(MillCommunityBuild.mapScalacOptions(_, value))
      |    .getOrElse {
      |        println("Failed to resolve scalaVersion, assume it's Scala 3 project")
      |        MillCommunityBuild.mapScalacOptions(sys.props.getOrElse("communitybuild.scala", "3.3.1"), value)
      |    }
      |  def mapScalacOptions(scalaVersion: String) = MillCommunityBuild.mapScalacOptions(scalaVersion, value)
      |}
      |
      |extension (task: mill.api.Task[Seq[String]]) {
      |  def mapScalacOptions(scalaVersion: mill.api.Task[String]) = mill.api.Task(
      |     MillCommunityBuild.mapScalacOptions(scalaVersion(), task())
      |  )
      |}
      """.stripMargin
    }

    def injects(injectRootModuleRunCommand: Boolean) = {
      Seq(
        if (isMill1x) None
        else
          Some(
            if (useLegacyTasks) """
            |import $file.MillCommunityBuild
            |import $file.MillVersionCompat, MillVersionCompat.compat.{Task => MillCompatTask}""".stripMargin
            else
              "\nimport MillVersionCompat.compat.{Task => MillCompatTask}"
          ),
        if (useLegacyTasks || isMill1x) None else Some("private object _OpenCommunityBuildOps {"),
        if (isMill1x && !injectRootModuleRunCommand) None else Some(MapScalacOptionsOps),
        if (useLegacyMillCross) Some(MillCommunityBuildCrossInject) else None,
        if (useLegacyTasks || isMill1x) None else Some("}\nimport _OpenCommunityBuildOps._"),
        if (injectRootModuleRunCommand) Some(MillCommunityBuildInject)
        else if (isMill1x) Some("import build.*")
        else None
      ).flatten ++
        Seq("// End of OpenCB code injects\n")
    }.mkString("\n")
  }

  object ValOrDefDef {
    def unapply(tree: Tree): Option[(Term.Name, Option[Type], Term)] = tree match {
      // Make sure def has no parameter lists
      case Defn.Def(_, name, _, Nil, tpe, body)               => Some((name, tpe, body))
      case Defn.Val(_, Pat.Var(name) :: Nil, tpe, body)       => Some((name, tpe, body))
      case Defn.Var(_, Pat.Var(name) :: Nil, tpe, Some(body)) => Some((name, tpe, body))
      case _                                                  => None
    }
  }

  def anyTreeOfTypeName(has: Seq[String], butNot: Seq[String] = Nil)(
      trees: List[Tree]
  ): Boolean = {
    val exists = trees.exists {
      case WithTypeName(name) => has.contains(name)
      case _                  => false
    }
    val existsBlocker = butNot.nonEmpty && trees.exists {
      case WithTypeName(name) => butNot.contains(name)
      case _                  => false
    }
    exists && !existsBlocker
  }

  object WithTypeName {
    def unapply(tree: Tree): Option[String] = tree match {
      case name: Type.Name         => Some(name.toString)
      case Type.Select(qual, name) => Some(name.toString)
      case Init(name, _, _)        => unapply(name)
      case _                       => None
    }
  }
}
