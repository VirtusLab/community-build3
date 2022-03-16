import org.json4s._
import org.json4s.native.Serialization
import org.json4s.ext.EnumSerializer

given Formats = Serialization.formats(NoTypeHints) + TestingModeEnumSerializer()

class TestingModeEnumSerializer
    extends CustomSerializer[TestingMode](format => {
      val DisabledName = "disabled"
      val CompileOnlyName = "compile-only"
      val FullName = "full"

      def deserialize: PartialFunction[JValue, TestingMode] = {
        case JString(DisabledName)    => TestingMode.Disabled
        case JString(CompileOnlyName) => TestingMode.CompileOnly
        case JString(FullName)        => TestingMode.Full
      }
      def serialize: PartialFunction[Any, JValue] = {
        case TestingMode.Disabled    => JString(DisabledName)
        case TestingMode.CompileOnly => JString(CompileOnlyName)
        case TestingMode.Full        => JString(FullName)
      }
      (deserialize, serialize)
    })

def toJson[T](obj: T): String = Serialization.write(obj)
