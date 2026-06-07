package millbuild

import mill.api.{Evaluator, Task}
import mill.scalalib.ScalaModule

import MillCommunityBuild.*

trait MillCommunityBuildYamlScalaMixin extends CommunityBuildScalaWorkerPathRefFix {
  override def scalacOptions =
    super.scalacOptions().mapScalacOptions(scalaVersion())
}

/** Nested declarative modules configured via `package.mill.yaml`. */
trait MillCommunityBuildYamlNestedPublishModule
    extends ScalaModule
    with MillCommunityBuildYamlScalaMixin
    with CommunityBuildPublishModule

trait MillCommunityBuildYamlNestedModule
    extends ScalaModule
    with MillCommunityBuildYamlScalaMixin
    with CommunityBuildCoursierModule

/** Root declarative module configured via `build.mill.yaml`. */
trait MillCommunityBuildYamlPublishModule extends MillCommunityBuildYamlNestedPublishModule {
  def runCommunityBuild(
      evaluator: Evaluator,
      scalaVersion: String,
      configJson: String,
      projectDir: String,
      targets: String*
  ) = Task.Command(exclusive = true) {
    given Ctx = MillCommunityBuild.Ctx(this, scalaVersion, evaluator, mill.Task.log)
    MillCommunityBuild.runBuild(configJson, projectDir, targets)
  }
}

trait MillCommunityBuildYamlModule extends MillCommunityBuildYamlNestedModule {
  def runCommunityBuild(
      evaluator: Evaluator,
      scalaVersion: String,
      configJson: String,
      projectDir: String,
      targets: String*
  ) = Task.Command(exclusive = true) {
    given Ctx = MillCommunityBuild.Ctx(this, scalaVersion, evaluator, mill.Task.log)
    MillCommunityBuild.runBuild(configJson, projectDir, targets)
  }
}
