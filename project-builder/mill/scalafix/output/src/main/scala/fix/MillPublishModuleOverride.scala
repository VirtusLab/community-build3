package fix

object MillPublishModuleOverride {
  object mill {
    trait PublishModule
    trait Foo
  }
  object MillCommunityBuild { trait CommunityBuildPublishModule }
  import mill._
  object module extends MillCommunityBuild.CommunityBuildPublishModule with Foo
  object module2 extends Foo with MillCommunityBuild.CommunityBuildPublishModule
}