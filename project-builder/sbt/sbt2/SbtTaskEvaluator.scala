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
        EvalResult.Failure(failureReasons(ex), (System.currentTimeMillis() - evalStart).toInt)
    }
  }

  // runTask throws the whole Incomplete tree, whose toString nests every node of the task
  // graph and buries the actual exception thousands of characters deep. Report the causes.
  private def failureReasons(ex: Throwable): List[Throwable] = ex match {
    case incomplete: Incomplete =>
      getAllDirectCauses(incomplete) match {
        case Nil    => ex :: Nil
        case causes => causes
      }
    case _ => ex :: Nil
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
