import sbt._

object SbtAdapterSupport {
  def projectRelation(extracted: Extracted) = {
    import extracted._
    sbt.Project.relation(extracted.structure, true)
  }

  // Platform to add to the artifact name on top of crossVersion. None: sbt 1 already encodes
  // the Scala.js / Scala Native platform in the module's crossVersion
  def platformToAdd(
      ref: ProjectRef,
      module: ModuleID,
      structure: sbt.internal.BuildStructure
  ): Option[String] = None
}
