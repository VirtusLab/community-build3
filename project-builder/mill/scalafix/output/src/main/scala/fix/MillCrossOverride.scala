package fix

object MillCrossOverride {
  object mill {
    abstract class Cross[T](args: Any*)
    abstract class ModuleType
  }

  sealed abstract class MillCommunityBuildCross[T](cases: Any*)(version: String)
  val versions = List()

  import mill._

  object module extends MillCommunityBuildCross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")
  object module2 extends MillCommunityBuildCross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")
  val module3 = new MillCommunityBuildCross[mill.ModuleType]("2.13.8", "3.1.0")("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD"){}
  object module4 extends MillCommunityBuildCross[ModuleType](versions:_*)("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")

}