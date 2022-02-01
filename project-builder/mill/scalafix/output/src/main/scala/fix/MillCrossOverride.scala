import $file.MillCommunityBuild
// Main entry point for community build
def runCommunityBuild(_evaluator: mill.eval.Evaluator, scalaVersion: String, targets: String*) = T.command {
  implicit val ctx = MillCommunityBuild.Ctx(this, scalaVersion, _evaluator, T.log)
  MillCommunityBuild.runBuild(targets)
}

// Replaces mill.define.Cross allowing to use map used cross versions
class MillCommunityBuildCross[T: _root_.scala.reflect.ClassTag]
  (cases: _root_.scala.Any*)
  (buildScalaVersion: _root_.java.lang.String)
  (implicit ci:  _root_.mill.define.Cross.Factory[T], ctx: _root_.mill.define.Ctx) 
  extends _root_.mill.define.Cross[T](
      MillCommunityBuild.mapCrossVersions(buildScalaVersion, cases): _*
    )
// End of code injects

package fix

object MillCrossOverride {
  object mill {
    abstract class Cross[T](args: Any*)
    abstract class ModuleType
  }
  import mill._

  object module extends MillCommunityBuildCross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)(_root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'")))
  object module2 extends MillCommunityBuildCross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)(_root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'")))
  val module3 = new MillCommunityBuildCross[mill.ModuleType]("2.13.8", "3.1.0")(_root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))){}
}