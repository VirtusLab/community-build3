package fix

object MillCrossOverride {
  object mill {
    abstract class Cross[T](args: Any*)
    abstract class ModuleType
  }

  sealed abstract class MillCommunityBuildCross[T](cases: Any*)(version: String)

  import mill._

  object module extends MillCommunityBuildCross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)(_root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'")))
  object module2 extends MillCommunityBuildCross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)(_root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'")))
  val module3 = new MillCommunityBuildCross[mill.ModuleType]("2.13.8", "3.1.0")(_root_.scala.sys.props.get("communitybuild.scala").getOrElse(_root_.scala.sys.error("Missing required property 'communitybuild.scala'"))){}
}