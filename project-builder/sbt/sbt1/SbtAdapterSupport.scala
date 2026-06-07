import sbt._

object SbtAdapterSupport {
  def projectRelation(extracted: Extracted) = {
    import extracted._
    sbt.Project.relation(extracted.structure, true)
  }
}
