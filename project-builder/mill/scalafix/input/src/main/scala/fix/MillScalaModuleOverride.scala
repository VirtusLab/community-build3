/*
rule = Scala3CommunityBuildMillAdapter
 */
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
  object module extends CoursierModule {}
  object module2 extends ScalaModule {}
  object module3 extends JavaModule {}
  object module4 extends PublishModule {}
  object module5 extends ScalaModule with JavaModule {}
  object module6 extends Foo with ScalaModule with JavaModule with PublishModule {}
  object module7 extends Foo with ScalaModule with JavaModule with PublishModule with CoursierModule {}

  trait testTrait extends CoursierModule {}
  trait testTrait2 extends ScalaModule {}
  trait testTrait3 extends JavaModule {}
  trait testTrait4 extends PublishModule {}
  trait testTrait5 extends ScalaModule with JavaModule {}
  trait testTrait6 extends Foo with ScalaModule with JavaModule with PublishModule {}
  trait testTrait7 extends Foo with ScalaModule with JavaModule with PublishModule with CoursierModule {}
}
