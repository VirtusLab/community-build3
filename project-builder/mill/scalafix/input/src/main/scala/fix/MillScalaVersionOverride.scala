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
  class moduleDef {
    def scalaVersion: T[String] = "3.0.1"
  }
  class moduleDef2 extends moduleDef {
    override val scalaVersion: T[String] = MillScalaVersionOverride.scala3Version
  }

  class otherDef {
    def scalaVersion() = "1.2.3"
  }

  val snippet = s"""
    def scalaVersion = "2.13.8"
  """
}
