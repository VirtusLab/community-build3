import sbt._
import sbt.Keys._
import Scala3CommunityBuild.{Utils => _, _}
import TaskEvaluator.EvalResult

class SbtTaskEvaluator(val project: ProjectRef, private var state: State)
    extends TaskEvaluator[TaskKey] {

  override def eval[T](task: TaskKey[T]): EvalResult[T] = {
    val evalStart = System.currentTimeMillis()
    val scopedTask = project / task

    sbt.Project
      .runTask(scopedTask, state)
      .fold[EvalResult[T]] {
        val reason = TaskEvaluator.UnkownTaskException(scopedTask.toString())
        EvalResult.Failure(reason :: Nil, 0)
      } { case (newState, result) =>
        val tookMs = (System.currentTimeMillis() - evalStart).toInt
        this.state = newState
        result.toEither match {
          case Right(value) => EvalResult.Value(value, tookMs)
          case Left(incomplete) =>
            val causes = getAllDirectCauses(incomplete)
            EvalResult.Failure(causes, tookMs)
        }
      }
  }

  private def getAllDirectCauses(incomplete: Incomplete): List[Throwable] = {
    val Limit = 10
    @scala.annotation.tailrec
    def loop(
        incomplete: List[Incomplete],
        acc: List[Throwable]
    ): List[Throwable] = {
      incomplete match {
        case Nil                     => acc
        case _ if acc.length > Limit => acc
        case head :: tail =>
          loop(
            incomplete = tail ::: head.causes.toList,
            acc = acc ::: head.directCause.toList
          )
      }
    }
    loop(incomplete :: Nil, Nil)
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
