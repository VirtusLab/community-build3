package fix

object MillScalaVersionOverride {
  def scala3Version = "3.1.1"

  object module {
    val scalaVersion = _root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))
  }
  class moduleDef {
    def scalaVersion: String = _root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))
  }
  class moduleDef2 extends moduleDef {
    override val scalaVersion: String = _root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))
  }

  val snippet = s"""
    def scalaVersion = "2.13.8"
  """
}