package fix

import scalafix.testkit._
import org.scalatest.FunSuiteLike

class RuleSuite extends AbstractSemanticRuleSuite with FunSuiteLike {
  sys.props.update("scalafix.mill.skipHeader", "true")
  runAllTests()
}
