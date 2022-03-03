package fix

object MillScalaVersionOverride {
  def scala3Version = "3.1.1"

  object module {
    val scalaVersion = "3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD"
  }
  class moduleDef {
    def scalaVersion: String = "3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD"
  }
  class moduleDef2 extends moduleDef {
    override val scalaVersion: String = "3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD"
  }

  val snippet = s"""
    def scalaVersion = "2.13.8"
  """
}