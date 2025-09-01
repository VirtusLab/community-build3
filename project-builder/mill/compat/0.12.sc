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

  @scala.annotation.nowarn
  trait ZincWorkerOverrideForScala3_8 extends ZincWorkerModule { self: CoursierModule =>
    import mill._
    import mill.scalalib.api.{ZincWorkerApi, ZincWorkerUtil}
    import mill.api.{PathRef, Ctx}

    override def worker: Worker[ZincWorkerApi] = Task.Worker {
      val jobs = Task.ctx() match {
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
              (ZincWorkerApi.Ctx, (String, String) => (Option[Agg[PathRef]], PathRef)),
              String => PathRef
            ]
          ], // compilerBridge
          classOf[Int], // jobs
          classOf[Boolean], // compileToJar
          classOf[Boolean], // zincLogDebug
          classOf[Option[PathRef]], // javaHome
          classOf[() => Unit]
        )
        .newInstance(
          Left(
            (
              Task.ctx(),
              (x: String, y: String) => scalaCompilerBridgeJar(x, y, defaultResolver())
            )
          ),
          jobs,
          java.lang.Boolean.FALSE,
          java.lang.Boolean.valueOf(zincLogDebug()),
          javaHome(),
          () => cl.close()
        ).asInstanceOf[ZincWorkerApi]
        
      ValPatcher.set(
        instance,
        "libraryJarNameGrep",
        (classpath: Agg[PathRef], version: String) => {
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
          ZincWorkerUtil.grepJar(
            classpath,
            "scala-library",
            searchVersion,
            sources = false
          ): PathRef
        }
      )
      instance
    }
  }

  private object ValPatcher {
    import java.lang.invoke.{MethodHandles, VarHandle}

    private lazy val unsafe: sun.misc.Unsafe = {
      val f = classOf[sun.misc.Unsafe].getDeclaredField("theUnsafe")
      f.setAccessible(true)
      f.get(null).asInstanceOf[sun.misc.Unsafe]
    }

    /** Set a (possibly private final) instance field by name. */
    def set[A <: AnyRef: scala.reflect.ClassTag](
        instance: AnyRef,
        fieldName: String,
        value: A
    ): Unit = {
      val cls = instance.getClass()
      val field = MethodHandles
        .privateLookupIn(cls, MethodHandles.lookup())
        .findVarHandle(cls, fieldName, implicitly[scala.reflect.ClassTag[A]].runtimeClass)

      // Reflect into the VarHandle implementation to get the native field offset.
      // JDKs name it "fieldOffset" or "offset".
      val varHandleOffsetField =
        Iterator("fieldOffset", "offset")
          .flatMap { name =>
            try Some(field.getClass().getDeclaredField(name))
            catch { case _: NoSuchFieldException => None }
          }
          .toList
          .headOption
          .orElse {
            // Fallback: first long field in the hierarchy (works on HotSpot’s Field* VarHandles)
            Iterator
              .iterate[Class[_]](field.getClass())(_.getSuperclass)
              .takeWhile(_ != null)
              .flatMap(_.getDeclaredFields.iterator)
              .find(_.getType == java.lang.Long.TYPE)
          }
          .getOrElse(
            throw new NoSuchFieldException(
              s"Couldn't find offset field in ${field.getClass().getName}"
            )
          )

      varHandleOffsetField.setAccessible(true)
      val offset = varHandleOffsetField.getLong(field)

      // Write the new value ignoring 'final'
      unsafe.putObjectVolatile(instance, offset, value)
      assert(field.get(instance) eq value)
    }
  }
}
