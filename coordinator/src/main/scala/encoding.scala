import org.json4s._
import org.json4s.native.Serialization
import org.json4s.ext.EnumSerializer
import java.time.OffsetDateTime

given Formats = Serialization.formats(
  NoTypeHints
) + TestingModeEnumSerializer() + UTCOffsetDateTimeSerializer()

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

def toJson[T <: AnyRef](obj: T, pretty: Boolean = false): String =
  if pretty
  then Serialization.writePretty(obj)
  else Serialization.write(obj)
def toJson[T <: AnyRef](obj: T): String = Serialization.write(obj)

def fromJson[T: Manifest](json: String): T = Serialization.read(json)

// Custom serializer in org.json4s.ext does not handle 2022-04-29T03:39:03Z
class UTCOffsetDateTimeSerializer
    extends CustomSerializer[OffsetDateTime](format => {
      def deserialize: PartialFunction[JValue, OffsetDateTime] = { case JString(value) =>
        OffsetDateTime.parse(value)
      }
      def serialize: PartialFunction[Any, JValue] = { case v: OffsetDateTime =>
        JString(v.toString())
      }
      (deserialize, serialize)
    })
