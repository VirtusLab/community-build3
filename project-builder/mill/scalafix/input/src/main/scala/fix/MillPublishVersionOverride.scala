/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillPublishVersionOverride {
  final val Version = "3.1.1"
  object mill{
    import scala.language.implicitConversions
    trait T[U]
    def T[U](v: U): U = ???
    implicit def anyToT[U](v:U): T[U] = ??? 
  }
  import mill._

  object module {
    val publishVersion = "3.0.0"
  }
  class moduleDef {
    def publishVersion: T[String] = "3.0.1"
  }
  class moduleDef2 extends moduleDef {
    override val publishVersion: T[String] = MillPublishVersionOverride.Version
  }

  class otherDef {
    def publishVersion() = "1.2.3"
  }

  val snippet = s"""
    def publishVersion = "2.13.8"
  """
}
