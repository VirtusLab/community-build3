package fix

object MillPublishVersionOverride {
  final val Version = "3.1.1"
  def T[U](v: U): U = ???

  object module {
    val publishVersion = "1.2.3-RC4"
  }
  class moduleDef {
    def publishVersion: String = "1.2.3-RC4"
  }
  class moduleDef2 extends moduleDef {
    override val publishVersion: String = "1.2.3-RC4"
  }

  val snippet = s"""
    def publishVersion = "2.13.8"
  """
}
