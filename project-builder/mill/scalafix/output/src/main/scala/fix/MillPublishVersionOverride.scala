package fix

object MillPublishVersionOverride {
  final val Version = "3.1.1"

  object module {
    val publishVersion = _root_.scala.sys.props.get("communitybuild.version").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.version'"))
  }
  class moduleDef {
    def publishVersion: String = _root_.scala.sys.props.get("communitybuild.version").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.version'"))
  }
  class moduleDef2 extends moduleDef {
    override val publishVersion: String = _root_.scala.sys.props.get("communitybuild.version").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.version'"))
  }

  val snippet = s"""
    def publishVersion = "2.13.8"
  """
}
