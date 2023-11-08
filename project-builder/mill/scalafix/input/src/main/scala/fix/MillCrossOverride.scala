/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillCrossOverride {
  object mill {
    abstract class Cross[T](args: Any*)
    abstract class ModuleType
  }

  object MillCommunityBuild{
    def mapCrossVersions[T](scalaBuildVersion: String, crossVersions: T*): Seq[T] = ???
  }
  val versions = List()

  import mill._

  object module extends Cross[ModuleType]((Seq("2.13.8", "3.0.0") ++ Option("3.1.0")): _*)
  object module2 extends _root_.fix.MillCrossOverride.mill.Cross[ModuleType]("2.13.8", "3.0.0", "3.1.0")
  val module3 = new mill.Cross[mill.ModuleType]("2.13.8", "3.1.0"){}
  object module4 extends Cross[ModuleType](versions:_*)

}
