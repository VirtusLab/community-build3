/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillScalaVersionOverride {
  def scala3Version = "3.1.1"

  object module {
    val scalaVersion = "3.0.0"
  }
  class moduleDef {
    def scalaVersion: String = "3.0.1"
  }
  class moduleDef2 extends moduleDef {
    override val scalaVersion: String = MillScalaVersionOverride.scala3Version
  }

  val snippet = s"""
    def scalaVersion = "2.13.8"
  """
}
