object compat {
  type CoursierModule = mill.scalalib.CoursierModule
  type JavaModule = mill.scalalib.JavaModule
  type PublishModule = mill.scalalib.PublishModule
  type ScalaModule = mill.scalalib.ScalaModule
  type TestModule = mill.scalalib.TestModule
  type ZincWorkerModule = mill.scalalib.ZincWorkerModule
  type TestResult = mill.scalalib.TestRunner.Result
  object Val {
    def unapply(v: Any) = Some(v)
  }
  type Task[+T] = mill.define.Task[T]

  def toZincWorker(v: ZincWorkerModule) = v
}
