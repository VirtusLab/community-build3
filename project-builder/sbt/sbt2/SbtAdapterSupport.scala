import sbt._

object SbtAdapterSupport {
  def projectRelation(extracted: Extracted) = {
    sbt.Project.relation(extracted.structure, true)(using extracted.showKey)
  }
}
