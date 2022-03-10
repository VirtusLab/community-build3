/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillPublishVersionOverride {
  final val Version = "3.1.1"
  def T[U](v: U): U = ???

  object module {
    val publishVersion = "3.0.0"
  }
  class moduleDef {
    def publishVersion: String = "3.0.1"
  }
  class moduleDef2 extends moduleDef {
    override val publishVersion: String = MillPublishVersionOverride.Version
  }

  val snippet = s"""
    def publishVersion = "2.13.8"
  """
}
