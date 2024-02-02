/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillScalacOptionsOverride {
  object MillCommunityBuild{
    implicit class MillCommunityBuildScalacOptionsOps(asSeq: Seq[String]){
      def mapScalacOptions(scalaVersion: String): Seq[String] = ???
    }
    implicit class MillCommunityBuildScalacOptionsTargetOps(asTarget: mill.T[Seq[String]]){
      def mapScalacOptions(scalaVersion: String): mill.T[Seq[String]] = ???
    }
  }
  import MillCommunityBuild._
  def scalaVersion: String = ???
  def scalacOptions = List.empty[String]
  object mill{
    import scala.language.implicitConversions
    trait T[U]
    def T[U](v: U): U = ???
    implicit def anyToT[U](v:U): T[U] = ??? 
    implicit def TToAny[U](v:T[U]): U = ??? 
  }
  import mill._

  object module {
    val scalacOptions = Nil
  }
  object module2 {
    def scalacOptions: Seq[String] = Seq("-Xprint:typer")
  }
  
  object module3 {
    def scalacOptions = mill.T(module2.scalacOptions)
  }
  
  class moduleDef {
    def scalacOptions: T[Seq[String]] = {
      val opt1 = "-release:11"
      Seq(opt1)
    }
  }
  class moduleDef2 extends moduleDef {
    override val scalacOptions: T[Seq[String]] = MillScalacOptionsOverride.scalacOptions
  }
  object moduleDef3 extends moduleDef {
    override def scalacOptions = T {
      super.scalacOptions ++ Nil
    }
  }
 
}
