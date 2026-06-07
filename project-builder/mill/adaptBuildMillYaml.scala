//> using dep org.yaml:snakeyaml:2.4
//> using scala 3.3

import org.yaml.snakeyaml.{DumperOptions, Yaml}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.CommandLineParser.FromString

given booleanFromString: FromString[Boolean] = _.toBooleanOption.getOrElse(false)

@main def adaptBuildMillYaml(
    file: String,
    scalaVersion: String,
    isRootBuild: Boolean = false
): Unit =
  val path = Paths.get(file)
  val original = Files.readString(path)
  val data = new Yaml().load[java.util.Map[String, Any]](original).asScala
  var changed = false

  data.get("scalaVersion").foreach { current =>
    if current.toString != scalaVersion then
      data("scalaVersion") = scalaVersion
      changed = true
  }

  data.get("extends").foreach { extendsValue =>
    val modules = moduleNames(extendsValue)
    if modules.exists(_.contains("ScalaModule")) then
      val traitName = communityBuildTrait(modules, isRootBuild)
      if !modules.contains(traitName) then
        data("extends") = traitName
        changed = true
  }

  if changed then
    val options = DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
    options.setPrettyFlow(true)
    options.setIndent(2)
    Files.writeString(path, new Yaml(options).dump(data.asJava))

def communityBuildTrait(modules: Seq[String], isRootBuild: Boolean): String =
  val publish = modules.exists(_.contains("PublishModule"))
  if isRootBuild then
    if publish then "millbuild.MillCommunityBuildYamlPublishModule"
    else "millbuild.MillCommunityBuildYamlModule"
  else if publish then "millbuild.MillCommunityBuildYamlNestedPublishModule"
  else "millbuild.MillCommunityBuildYamlNestedModule"

def moduleNames(extendsValue: Any): Seq[String] = extendsValue match
  case name: String => Seq(name)
  case list: java.util.List[?] => list.asScala.toSeq.map(_.toString)
  case _ => Nil
