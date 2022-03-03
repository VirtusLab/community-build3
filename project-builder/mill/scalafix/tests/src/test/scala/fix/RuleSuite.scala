package fix

import scalafix.testkit._
import org.scalatest.FunSuiteLike

class RuleSuite extends AbstractSemanticRuleSuite with FunSuiteLike {
  sys.props.update("communitybuild.noInjects", "true")
  sys.props.update("communitybuild.scala", "3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")
  runAllTests()
}
