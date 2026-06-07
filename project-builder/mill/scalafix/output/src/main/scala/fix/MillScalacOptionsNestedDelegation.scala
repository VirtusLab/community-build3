package fix

object MillScalacOptionsNestedDelegation {
  object MillCommunityBuild {
    trait CommunityBuildPublishModule
    trait CommunityBuildCoursierModule
    trait CommunityBuildScalaWorkerPathRefFix
    implicit class MillCommunityBuildScalacOptionsOps(asSeq: Seq[String]) { def mapScalacOptions(scalaVersion: String): Seq[String] = ??? }
    implicit class MillCommunityBuildScalacOptionsTargetOps(asTarget: mill.Task[Seq[String]]) { def mapScalacOptions(scalaVersion: mill.Task[String]): mill.Task[Seq[String]] = ??? }
  }
  import MillCommunityBuild._
  object mill {
    import scala.language.implicitConversions
    trait Task[T]
    object Task { def apply[T](t: => T): Task[T] = ??? }
    trait PublishModule
    trait ScalaModule {
      def scalaVersion: Task[String]
      def scalacOptions: Task[Seq[String]]
    }
    trait ScalaTests {
      def scalaVersion: Task[String]
      def scalacOptions: Task[Seq[String]]
    }
    implicit def anyToTask[T](v: T): Task[T] = ???
  }
  import mill._
  val scala3 = "3.8.4"
  trait CommonBase extends ScalaModule with MillCommunityBuild.CommunityBuildPublishModule with MillCommunityBuild.CommunityBuildScalaWorkerPathRefFix { common =>
    def scalaVersion = scala3
    def scalacOptions = Task {
      Seq("-Wunused:privates,locals,explicits,implicits,params").mapScalacOptions(this.scalaVersion())
    }
    trait CommonTest extends ScalaTests with MillCommunityBuild.CommunityBuildCoursierModule with MillCommunityBuild.CommunityBuildScalaWorkerPathRefFix { def scalacOptions = common.scalacOptions }
  }
}
