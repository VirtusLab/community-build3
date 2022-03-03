/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillCrossOverride {
  object mill {
    abstract class Cross[T](args: Any*)
    abstract class ModuleType
  }

  sealed abstract class MillCommunityBuildCross[T](cases: Any*)(version: String)
  val versions = List()

  import mill._

  object module extends Cross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)
  object module2 extends _root_.fix.MillCrossOverride.mill.Cross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)
  val module3 = new mill.Cross[mill.ModuleType]("2.13.8", "3.1.0"){}
  object module4 extends Cross[ModuleType](versions:_*)

}
