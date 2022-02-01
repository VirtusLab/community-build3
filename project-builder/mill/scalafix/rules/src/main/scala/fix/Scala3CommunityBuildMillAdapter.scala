package fix

import scalafix.v1._
import scala.meta._

class Scala3CommunityBuildMillAdapter extends SyntacticRule("Scala3CommunityBuildMillAdapter") {
  override def fix(implicit doc: SyntacticDocument): Patch = {
    Patch.addLeft(doc.tree, Replacment.MillCommunityBuildInject) +
      doc.tree.collect {
        case init @ Init(
              Type.Apply(
                name @ (Type.Name("Cross") | Type.Select(_, Type.Name("Cross"))),
                List(tpeParam)
              ),
              _,
              List(args)
            ) =>
          List(
            Patch.replaceTree(name, Replacment.CommunityBuildCross),
            Patch.addRight(init, s"(${Replacment.DiscoverScalaVersion})")
          ).asPatch

        case Init(
              name @ (Type.Name("PublishModule") | Type.Select(_, Type.Name("PublishModule"))),
              _,
              _
            ) =>
          Patch.replaceTree(name, Replacment.CommunityBuildPublishModule)

        case ValOrDefDef(Term.Name("scalaVersion"), body) =>
          Patch.replaceTree(body, Replacment.DiscoverScalaVersion)

        case ValOrDefDef(Term.Name("publishVersion"), body) =>
          Patch.replaceTree(body, Replacment.DiscoverPublishVersion)

      }.asPatch
  }

  object Replacment {
    val CommunityBuildCross = "MillCommunityBuildCross"
    val CommunityBuildPublishModule = "MillCommunityBuild.CommunityBuildPublishModule"
    val DiscoverScalaVersion = getPropertyOrThrow("communitybuild.scala")
    val DiscoverPublishVersion = getPropertyOrThrow("communitybuild.version")
    val MillCommunityBuildInject = """
    |import $file.MillCommunityBuild
    |// Main entry point for community build
    |def runCommunityBuild(_evaluator: mill.eval.Evaluator, scalaVersion: String, targets: String*) = T.command {
    |  implicit val ctx = MillCommunityBuild.Ctx(this, scalaVersion, _evaluator, T.log)
    |  MillCommunityBuild.runBuild(targets)
    |}
    |
    |// Replaces mill.define.Cross allowing to use map used cross versions
    |class MillCommunityBuildCross[T: _root_.scala.reflect.ClassTag]
    |  (cases: _root_.scala.Any*)
    |  (buildScalaVersion: _root_.java.lang.String)
    |  (implicit ci:  _root_.mill.define.Cross.Factory[T], ctx: _root_.mill.define.Ctx) 
    |  extends _root_.mill.define.Cross[T](
    |      MillCommunityBuild.mapCrossVersions(buildScalaVersion, cases): _*
    |    )
    |// End of code injects
    |""".stripMargin

    private def getPropsOrElse(propertyName: String)(orElse: String) = {
      s"""_root_.scala.sys.props.get("$propertyName").getOrElse($orElse)"""
    }
    private def throwMisingProperty(propertyName: String) = {
      s"""_root_.scala.sys.error("Missing required property '$propertyName'")"""
    }
    private def getPropertyOrThrow(propertyName: String) =
      getPropsOrElse(propertyName)(throwMisingProperty(propertyName))

  }

  object ValOrDefDef {
    def unapply(tree: Tree): Option[(Term.Name, Term)] = tree match {
      case Defn.Def(_, name, _, _, _, body)           => Some(name -> body)
      case Defn.Val(_, Pat.Var(name) :: Nil, _, body) => Some(name -> body)
      case Defn.Var(_, Pat.Var(name) :: Nil, _, Some(body)) =>
        Some(name -> body)
      case _ => None
    }
  }

}
