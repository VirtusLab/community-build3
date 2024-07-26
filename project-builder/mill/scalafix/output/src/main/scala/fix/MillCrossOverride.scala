package fix

object MillCrossOverride {
  object mill {
    abstract class Cross[T](args: Any*)
    abstract class ModuleType
  }
  object MillCommunityBuild { def mapCrossVersions[T](scalaBuildVersion: String, crossVersions: T*): Seq[T] = ??? }
  val versions = List()
  import mill._
  object module extends Cross[ModuleType](MillCommunityBuild.mapCrossVersions("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD", Seq("2.13.8", "3.0.0") ++ Option("3.1.0"): _*))
  object module2 extends _root_.fix.MillCrossOverride.mill.Cross[ModuleType](MillCommunityBuild.mapCrossVersions("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD", "2.13.8", "3.0.0", "3.1.0"))
  object module4 extends Cross[ModuleType](MillCommunityBuild.mapCrossVersions("3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD", versions: _*))
}