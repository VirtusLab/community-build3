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

object MillPublishVersionOverride {
  final val Version = "3.1.1"

  object module {
    val publishVersion = _root_.scala.sys.props.get("communityBuild.version").getOrElse(_root_.scala.sys.error("Missing required property 'communityBuild.version'"))
  }
  class moduleDef {
    def publishVersion: String = _root_.scala.sys.props.get("communityBuild.version").getOrElse(_root_.scala.sys.error("Missing required property 'communityBuild.version'"))
  }
  class moduleDef2 extends moduleDef {
    override val publishVersion: String = _root_.scala.sys.props.get("communityBuild.version").getOrElse(_root_.scala.sys.error("Missing required property 'communityBuild.version'"))
  }

  val snippet = s"""
    def publishVersion = "2.13.8"
  """
}
