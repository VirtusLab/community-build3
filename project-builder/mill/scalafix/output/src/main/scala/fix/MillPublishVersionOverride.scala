package fix

object MillPublishVersionOverride {
  final val Version = "3.1.1"
  def T[U](v: U): U = ???

  object module {
    val publishVersion = T{_root_.scala.sys.props.get("communitybuild.version").getOrElse("3.0.0")}
  }
  class moduleDef {
    def publishVersion: String = T{_root_.scala.sys.props.get("communitybuild.version").getOrElse("3.0.1")}
  }
  class moduleDef2 extends moduleDef {
    override val publishVersion: String = T{_root_.scala.sys.props.get("communitybuild.version").getOrElse(MillPublishVersionOverride.Version)}
  }

  val snippet = s"""
    def publishVersion = "2.13.8"
  """
}
