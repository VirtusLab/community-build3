import sbt._

object SbtAdapterSupport {
  def projectRelation(extracted: Extracted) = {
    sbt.Project.relation(extracted.structure, true)(using extracted.showKey)
  }

  // Platform to add to the artifact name on top of crossVersion. sbt 2 keeps the Scala.js / Scala Native
  // platform out of crossVersion, in ModuleID.platformOpt or the project's `platform` setting. None for JVM modules.
  def platformToAdd(
      ref: ProjectRef,
      module: ModuleID,
      structure: sbt.internal.BuildStructure
  ): Option[String] =
    module.platformOpt
      .orElse((ref / Keys.platform).get(structure.data))
      .filterNot(_ == sbt.librarymanagement.Platform.jvm)
}
