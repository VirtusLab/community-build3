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
    val scalaVersion = mill.T("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")
  }
  class moduleDef {
    def scalaVersion: T[String] = mill.T("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")
  }
  class moduleDef2 extends moduleDef {
    override val scalaVersion: T[String] = mill.T("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")
  }

  class otherDef {
    def scalaVersion() = "1.2.3"
  }

  val snippet = s"""
    def scalaVersion = "2.13.8"
  """
}