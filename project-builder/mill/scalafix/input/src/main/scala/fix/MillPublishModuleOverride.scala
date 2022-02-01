/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillPublishModuleOverride {
  object mill {
    trait PublishModule
    trait Foo
  }

  import mill._
  object module extends PublishModule with Foo
  object module2 extends Foo with _root_.fix.MillPublishModuleOverride.mill.PublishModule
  val module3 = new PublishModule{}
}
