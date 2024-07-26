/*
rule = Scala3CommunityBuildMillAdapter
*/
package fix

object MillPublishModuleOverride {
  object mill {
    trait PublishModule
    trait Foo
  }

  object MillCommunityBuild {
    trait CommunityBuildPublishModule
  }

  import mill._
  object module extends PublishModule with Foo
  object module2 extends Foo with mill.PublishModule
}
