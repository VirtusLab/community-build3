package fix

import scalafix.testkit._
import org.scalatest.funsuite.AnyFunSuiteLike

class RuleSuite extends AbstractSemanticRuleSuite with AnyFunSuiteLike {
  sys.props.update("communitybuild.noInjects", "true")
  sys.props.update("communitybuild.scala", "3.1.2-RC2-bin-cb00abcdef123456789-COMMUNITY-BUILD")
  sys.props.update("communitybuild.version", "1.2.3-RC4")

  runAllTests()
}
