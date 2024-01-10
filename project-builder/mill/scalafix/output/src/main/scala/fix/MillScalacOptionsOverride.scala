package fix

object MillScalacOptionsOverride {
  object MillCommunityBuild{
    implicit class MillCommunityBuildScalacOptionsOps(asSeq: Seq[String]){
      def mapScalacOptions(): Seq[String] = ???
    }
    implicit class MillCommunityBuildScalacOptionsTargetOps(asTarget: mill.T[Seq[String]]){
      def mapScalacOptions(): mill.T[Seq[String]] = ???
    }
  }
  import MillCommunityBuild._
  def scalacOptions = { List.empty[String] }.mapScalacOptions()
  object mill{
    import scala.language.implicitConversions
    trait T[U]
    def T[U](v: U): U = ???
    implicit def anyToT[U](v:U): T[U] = ??? 
    implicit def TToAny[U](v:T[U]): U = ??? 
  }
  import mill._

  object module {
    val scalacOptions = { Nil }.mapScalacOptions()
  }
  object module2 {
    def scalacOptions: Seq[String] = { Seq("-Xprint:typer") }.mapScalacOptions()
  }
  
  object module3 {
    def scalacOptions = { mill.T(module2.scalacOptions) }.mapScalacOptions()
  }
  
  class moduleDef {
    def scalacOptions: T[Seq[String]] = { {
      val opt1 = "-release:11"
      Seq(opt1)
    } }.mapScalacOptions()
  }
  class moduleDef2 extends moduleDef {
    override val scalacOptions: T[Seq[String]] = { MillScalacOptionsOverride.scalacOptions }.mapScalacOptions()
  }
  object moduleDef3 extends moduleDef {
    override def scalacOptions = { T {
      super.scalacOptions ++ Nil
    } }.mapScalacOptions()
  }
 
}
