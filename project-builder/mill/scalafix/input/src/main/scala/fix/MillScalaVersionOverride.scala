/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillScalaVersionOverride {
  def scala3Version = "3.1.1"
  object mill{
    import scala.language.implicitConversions
    trait T[U]
    def T[U](v: U): U = ???
    implicit def anyToT[U](v:U): T[U] = ??? 
  }
  import mill._

  object module {
    val scalaVersion = "3.0.0"
  }
  object module2 {
    val scalaVersion: String = "3.0.0"
  }
  class moduleDef {
    def scalaVersion: T[String] = "3.0.1"
  }
  class moduleDef2 extends moduleDef {
    override val scalaVersion: T[String] = MillScalaVersionOverride.scala3Version
  }
  class moduleDef3 extends moduleDef {
    override val scalaVersion: T[String] = "3.2.1-RC1"
  }
  class moduleDef4 extends moduleDef {
    override val scalaVersion = sv
    def sv = scala3
    def scala3 = "3.1.3"
  }

  class otherDef {
    def scalaVersion() = "1.2.3"
  }

  object unchanged{
    object Scala{def scala213 = "2.13.8"}
    import Scala._
    class case1 { def scalaVersion: T[String] = "2.13.8" }
    class case2 { def scalaVersion: T[String] = scala213 }
    class case3 { def scalaVersion: T[String] = Scala.scala213 }
    class case4 {
      val scalaVersion = sv
      def sv = scala213
      def scala213 = "2.13.8"
    }
  }

  val snippet = s"""
    def scalaVersion = "2.13.8"
  """
}
