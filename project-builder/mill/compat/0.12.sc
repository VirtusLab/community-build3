package build
// Same as 0.11
object compat {
  type CoursierModule = mill.scalalib.CoursierModule
  type JavaModule = mill.scalalib.JavaModule
  type PublishModule = mill.scalalib.PublishModule
  type ScalaModule = mill.scalalib.ScalaModule
  type TestModule = mill.scalalib.TestModule
  type ZincWorkerModule = mill.scalalib.ZincWorkerModule
  type TestResult = mill.testrunner.TestResult
  type Val = mill.api.Val
  val Val = mill.api.Val
  type Task[+T] = mill.Task[T]

  def toZincWorker(v: ZincWorkerModule) = mill.define.ModuleRef(v)
}