package fix

object MillScalacOptionsOverride {
  def scalacOptions = { List.empty[String] }.diff(Seq("R1","R2")).diff(Seq("A1","A2")).appendedAll(Seq("A1","A2")).distinct
  object mill{
    import scala.language.implicitConversions
    trait T[U]
    def T[U](v: U): U = ???
    implicit def anyToT[U](v:U): T[U] = ??? 
    implicit def TToAny[U](v:T[U]): U = ??? 
  }
  import mill._

  object module {
    val scalacOptions = { Nil }.diff(Seq("R1","R2")).diff(Seq("A1","A2")).appendedAll(Seq("A1","A2")).distinct
  }
  object module2 {
    def scalacOptions: Seq[String] = { Seq("-Xprint:typer") }.diff(Seq("R1","R2")).diff(Seq("A1","A2")).appendedAll(Seq("A1","A2")).distinct
  }
  class moduleDef {
    def scalacOptions: T[Seq[String]] = {
      { val opt1 = "-release:11"
      Seq(opt1) }.diff(Seq("R1","R2")).diff(Seq("A1","A2")).appendedAll(Seq("A1","A2")).distinct
    }
  }
  class moduleDef2 extends moduleDef {
    override val scalacOptions: T[Seq[String]] = { MillScalacOptionsOverride.scalacOptions }.diff(Seq("R1","R2")).diff(Seq("A1","A2")).appendedAll(Seq("A1","A2")).distinct
  }
  object moduleDef3 extends moduleDef {
    override def scalacOptions = T {
      { super.scalacOptions ++ Nil }.diff(Seq("R1","R2")).diff(Seq("A1","A2")).appendedAll(Seq("A1","A2")).distinct
    }
  }
 
}
