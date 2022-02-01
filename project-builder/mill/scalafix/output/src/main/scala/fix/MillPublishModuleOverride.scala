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

object MillPublishModuleOverride {
  object mill {
    trait PublishModule
    trait Foo
  }

  import mill._
  object module extends MillCommunityBuild.CommunityBuildPublishModule with Foo
  object module2 extends Foo with MillCommunityBuild.CommunityBuildPublishModule
  val module3 = new MillCommunityBuild.CommunityBuildPublishModule{}
}
