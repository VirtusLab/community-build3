import sjsonnew._
import sjsonnew.BasicJsonProtocol._
import Scala3CommunityBuild._

object CommunityBuildConfigFormats {
  implicit object TestingModeEnumJsonFormat extends JsonFormat[TestingMode] {
    def write[J](x: TestingMode, builder: Builder[J]): Unit = ???
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): TestingMode =
      jsOpt.fold(deserializationError("Missing string")) { js =>
        unbuilder.readString(js) match {
          case "disabled"     => TestingMode.Disabled
          case "compile-only" => TestingMode.CompileOnly
          case "full"         => TestingMode.Full
        }
      }
  }

  implicit object ProjectOverridesFormat extends JsonFormat[ProjectOverrides] {
    def write[J](obj: ProjectOverrides, builder: Builder[J]): Unit = ???
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): ProjectOverrides =
      jsOpt.fold(deserializationError("Empty object")) { js =>
        implicit val _unbuilder: Unbuilder[J] = unbuilder
        unbuilder.beginObject(js)
        val testsMode = readOrDefault("tests", Option.empty[TestingMode])
        unbuilder.endObject()
        ProjectOverrides(tests = testsMode)
      }
  }

  implicit object ProjectsConfigFormat extends JsonFormat[ProjectsConfig] {
    def write[J](obj: ProjectsConfig, builder: Builder[J]): Unit = ???
    def read[J](jsOpt: Option[J], unbuilder: Unbuilder[J]): ProjectsConfig =
      jsOpt.fold(deserializationError("Empty object")) { js =>
        implicit val _unbuilder: Unbuilder[J] = unbuilder
        unbuilder.beginObject(js)
        val excluded = readOrDefault("exclude", Array.empty[String])
        val overrides =
          readOrDefault("overrides", Map.empty[String, ProjectOverrides])
        unbuilder.endObject()
        ProjectsConfig(excluded.toList, overrides)
      }
  }

  implicit object ProjectBuildConfigFormat extends JsonFormat[ProjectBuildConfig] {
    def write[J](v: ProjectBuildConfig, builder: Builder[J]): Unit = ???
    def read[J](
        optValue: Option[J],
        unbuilder: Unbuilder[J]
    ): ProjectBuildConfig =
      optValue.fold(deserializationError("Empty object")) { v =>
        implicit val _unbuilder: Unbuilder[J] = unbuilder
        unbuilder.beginObject(v)
        val projects = readOrDefault("projects", ProjectsConfig())
        val testsMode = readOrDefault[TestingMode, J]("tests", TestingMode.Full)
        unbuilder.endObject()
        ProjectBuildConfig(projects, testsMode)
      }
  }

  private def readOrDefault[T, J](field: String, default: T)(implicit
      format: JsonReader[T],
      unbuilder: Unbuilder[J]
  ): T =
    unbuilder
      .lookupField(field)
      .fold(default) { v =>
        import scala.util.Try
        Try(format.read(Some(v), unbuilder)).getOrElse(default)
      }
}
