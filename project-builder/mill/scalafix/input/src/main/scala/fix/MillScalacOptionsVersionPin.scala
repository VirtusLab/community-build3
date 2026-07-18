/*
rule = Scala3CommunityBuildMillAdapter
Scala3CommunityBuildMillAdapter.millBinaryVersion = "1.0"
*/
package fix

object MillScalacOptionsVersionPin {
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

  object DepVersions {
    def scalacOptionsVersion: String = "0.1.8"
    def osLibVersion: String = "0.11.8"
  }

  // Named like compiler options, but this is a dependency version pin.
  object Dependencies {
    val oslib: String = DepVersions.osLibVersion
    val scalacOptions: String = DepVersions.scalacOptionsVersion
  }

  trait Shared extends ScalaModule {
    override def scalaVersion = mill.Task("3.8.4")
    override def scalacOptions = Task(Seq("-Werror"))
  }
}
