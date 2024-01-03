package fix

object MillScalacOptionsOverride {
  object MillCommunityBuild{
    def mapScalacOptions(current: Seq[String]): Seq[String] = ???
  }
  def scalacOptions = MillCommunityBuild.mapScalacOptions{ List.empty[String] }
  object mill{
    import scala.language.implicitConversions
    trait T[U]
    def T[U](v: U): U = ???
    implicit def anyToT[U](v:U): T[U] = ??? 
    implicit def TToAny[U](v:T[U]): U = ??? 
  }
  import mill._

  object module {
    val scalacOptions = MillCommunityBuild.mapScalacOptions{ Nil }
  }
  object module2 {
    def scalacOptions: Seq[String] = MillCommunityBuild.mapScalacOptions{ Seq("-Xprint:typer") }
  }
  class moduleDef {
    def scalacOptions: T[Seq[String]] = MillCommunityBuild.mapScalacOptions{ {
      val opt1 = "-release:11"
      Seq(opt1)
    } }
  }
  class moduleDef2 extends moduleDef {
    override val scalacOptions: T[Seq[String]] = MillCommunityBuild.mapScalacOptions{ MillScalacOptionsOverride.scalacOptions }
  }
  object moduleDef3 extends moduleDef {
    override def scalacOptions = T {
      MillCommunityBuild.mapScalacOptions{ super.scalacOptions ++ Nil }
    }
  }
 
}
