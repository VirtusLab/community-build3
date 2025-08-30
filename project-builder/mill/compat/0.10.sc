object compat {
  type CoursierModule = mill.scalalib.CoursierModule
  type JavaModule = mill.scalalib.JavaModule
  type PublishModule = mill.scalalib.PublishModule
  type ScalaModule = mill.scalalib.ScalaModule
  type TestModule = mill.scalalib.TestModule
  type ZincWorkerModule = mill.scalalib.ZincWorkerModule
  type TestResult = mill.testrunner.TestRunner.Result
  object Val {
    def unapply(v: Any) = Some(v)
  }
  type Task[+T] = mill.define.Task[T]

  def toZincWorker(v: ZincWorkerModule) = v
  trait ZincWorkerOverrideForScala3_8 extends ZincWorkerModule { self: CoursierModule =>
    import mill._
    import mill.define.Worker
    import mill.scalalib.api.{ZincWorkerApi, ZincWorkerUtil}
    import mill.api.{Ctx, FixSizedCache, KeyedLockedCache, PathRef}

    override def worker: Worker[ZincWorkerApi] = T.worker {
      scala.util
        .Try {
          val ctx = T.ctx()
          val jobs = T.ctx() match {
            case j: Ctx.Jobs => j.jobs
            case _           => 1
          }
          val cl = mill.api.ClassLoader.create(
            classpath().map(_.path.toNIO.toUri.toURL).iterator.to(Vector),
            getClass.getClassLoader
          )

          val cls = cl.loadClass("mill.scalalib.worker.ZincWorkerImpl")
          val instance = cls
            .getConstructor(
              classOf[
                Either[
                  (ZincWorkerApi.Ctx, (String, String) => (Option[Array[os.Path]], os.Path)),
                  String => os.Path
                ]
              ], // compilerBridge
              classOf[(Agg[os.Path], String) => os.Path], // libraryJarNameGrep
              classOf[(Agg[os.Path], String) => os.Path], // compilerJarNameGrep
              classOf[KeyedLockedCache[_]], // compilerCache
              classOf[Boolean], // compileToJar
              classOf[Boolean] // zincLogDebug
            )
            .newInstance(
              Left(
                (
                  T.ctx(),
                  (x: String, y: String) =>
                    scalaCompilerBridgeJar(x, y, repositoriesTask()).asSuccess.get.value
                )
              ),
              (classpath: Agg[os.Path], version: String) => {
                // The only difference from upstream worker
                val searchVersion = sys.props
                  .get("communitybuild.scala")
                  .map(_.split("\\.").take(2).map(_.toInt).toList)
                  .filter(_.length == 2)
                  .orElse {
                    System.err.println(
                      "No communitybuild.scala sys prop, mill worker workaround would not work"
                    )
                    None
                  }
                  .collectFirst { case Seq(3, minor) if minor >= 8 => "3." }
                  .getOrElse(version)
                ZincWorkerUtil.grepJar(classpath, "scala-library", searchVersion, sources = false)
                // End of patched segment
              },
              ZincWorkerUtil.grepJar(_, "scala-compiler", _, sources = false),
              new FixSizedCache(jobs),
              java.lang.Boolean.FALSE,
              java.lang.Boolean.valueOf(zincLogDebug())
            )
          instance.asInstanceOf[ZincWorkerApi]
        }
        .getOrElse(super.worker())
    }
  }
}
