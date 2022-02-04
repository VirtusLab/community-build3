package fix

object MillCoursierModuleOverride {
  object mill {
    trait CoursierModule
    trait JavaModule // extends CoursierModule
    trait ScalaModule // extends JavaModule
    trait PublishModule // extends JavaModule
    sealed class Foo
  }

  object MillCommunityBuild {
    trait CommunityBuildPublishModule
    trait CommunityBuildCoursierModule
  }

  import mill._
  object module extends MillCommunityBuild.CommunityBuildCoursierModule {}
  object module2 extends ScalaModule with MillCommunityBuild.CommunityBuildCoursierModule {}
  object module3 extends JavaModule with MillCommunityBuild.CommunityBuildCoursierModule {}
  object module4 extends MillCommunityBuild.CommunityBuildPublishModule {}
  object module5 extends ScalaModule with JavaModule with MillCommunityBuild.CommunityBuildCoursierModule {}
  object module6 extends Foo with ScalaModule with JavaModule with MillCommunityBuild.CommunityBuildPublishModule {}
  object module7 extends Foo with ScalaModule with JavaModule with MillCommunityBuild.CommunityBuildPublishModule with MillCommunityBuild.CommunityBuildCoursierModule {}

  trait testTrait extends MillCommunityBuild.CommunityBuildCoursierModule {}
  trait testTrait2 extends ScalaModule with MillCommunityBuild.CommunityBuildCoursierModule {}
  trait testTrait3 extends JavaModule with MillCommunityBuild.CommunityBuildCoursierModule {}
  trait testTrait4 extends MillCommunityBuild.CommunityBuildPublishModule {}
  trait testTrait5 extends ScalaModule with JavaModule with MillCommunityBuild.CommunityBuildCoursierModule {}
  trait testTrait6 extends Foo with ScalaModule with JavaModule with MillCommunityBuild.CommunityBuildPublishModule {}
  trait testTrait7 extends Foo with ScalaModule with JavaModule with MillCommunityBuild.CommunityBuildPublishModule with MillCommunityBuild.CommunityBuildCoursierModule {}
}
