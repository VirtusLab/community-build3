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

object MillScalaVersionOverride {
  def scala3Version = "3.1.1"

  object module {
    val scalaVersion = _root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))
  }
  class moduleDef {
    def scalaVersion: String = _root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))
  }
  class moduleDef2 extends moduleDef {
    override val scalaVersion: String = _root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))
  }

  val snippet = s"""
    def scalaVersion = "2.13.8"
  """
}