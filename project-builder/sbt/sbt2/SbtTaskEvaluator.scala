import sbt._
import sbt.Keys._
import Scala3CommunityBuild.{Utils => _, _}
import TaskEvaluator.EvalResult

class SbtTaskEvaluator(val project: ProjectRef, private var state: State)
    extends TaskEvaluator[TaskKey] {

  override def eval[T](task: TaskKey[T]): EvalResult[T] = {
    val evalStart = System.currentTimeMillis()
    val scopedTask = project / task
    val extracted = sbt.Project.extract(state)

    try {
      val (newState, value) = extracted.runTask(scopedTask, state)
      val tookMs = (System.currentTimeMillis() - evalStart).toInt
      this.state = newState
      EvalResult.Value(value, tookMs)
    } catch {
      case ex: Throwable =>
        EvalResult.Failure(ex :: Nil, (System.currentTimeMillis() - evalStart).toInt)
    }
  }
}

object WithExtractedScala3Suffix {
  def unapply(s: String): Option[(String, String)] = {
    val parts = s.split("_")
    if (parts.length > 1 && parts.last.startsWith("3")) {
      Some((parts.init.mkString("_"), parts.last))
    } else {
      None
    }
  }
}
