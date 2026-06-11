/*
rule = Scala3CommunityBuildMillAdapter
Scala3CommunityBuildMillAdapter.millBinaryVersion = "1.0"
*/
package fix

object MillScalacOptionsCommonObject {
  object MillCommunityBuild {
    trait CommunityBuildCoursierModule
    trait CommunityBuildScalaWorkerPathRefFix
    implicit class MillCommunityBuildScalacOptionsOps(asSeq: Seq[String]) {
      def mapScalacOptions(scalaVersion: String): Seq[String] = ???
      def mapScalacOptions(scalaVersion: mill.Task[String]): Seq[String] = ???
    }
  }
  import MillCommunityBuild._
  object mill {
    trait Task[T]
    object Task { def apply[T](t: => T): Task[T] = ??? }
    extension [T](task: Task[T]) {
      def apply(): Task[T] = task
    }
    trait ScalaModule {
      def scalaVersion: Task[String]
      def scalacOptions: Task[Seq[String]]
    }
  }
  import mill._

  object Common {
    val scalaVersion = "3.9.0-RC1"
    val scalacOptions = Seq.empty[String]
  }

  trait CommonModule extends ScalaModule {
    override def scalaVersion = mill.Task("3.9.0-RC1")
    override def scalacOptions = Task(Common.scalacOptions)
  }

  object test extends ScalaModule {
    override def scalaVersion = mill.Task("3.9.0-RC1")
    override def scalacOptions = Task(Common.scalacOptions)
  }
}
