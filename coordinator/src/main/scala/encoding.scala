import org.json4s._
import org.json4s.native.Serialization
import java.time.OffsetDateTime

given Serialization = org.json4s.native.Serialization
given Formats =
  Serialization.formats(NoTypeHints) +
    TestingModeEnumSerializer() + ProjectBuildDefSerializer +
    UTCOffsetDateTimeSerializer() + ProjectSerializer()

object ProjectBuildDefSerializer
    extends FieldSerializer[ProjectBuildDef]({
      import FieldSerializer.ignore
      ignore("dependencies")
    })

class ProjectSerializer
    extends CustomSerializer[Project](_ => {
      def deserialize: PartialFunction[JValue, Project] = { case JString(stringValue) =>
        Project.load(stringValue)
      }
      def serialize: PartialFunction[Any, JValue] = { case p: Project =>
        JString(p.coordinates)
      }
      (deserialize, serialize)
    })

class TestingModeEnumSerializer
    extends CustomSerializer[TestingMode](_ => {
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
    extends CustomSerializer[OffsetDateTime](_ => {
      def deserialize: PartialFunction[JValue, OffsetDateTime] = { case JString(value) =>
        OffsetDateTime.parse(value)
      }
      def serialize: PartialFunction[Any, JValue] = { case v: OffsetDateTime =>
        JString(v.toString())
      }
      (deserialize, serialize)
    })
