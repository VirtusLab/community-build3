import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import scala.concurrent.duration.*

/** Periodic heartbeat for long coordinator runs (dependency graph + build plan). */
object CoordinatorProgress:
  private val startedAtMs = AtomicLong(0L)
  private val phase = AtomicReference("idle")
  private val detail = AtomicReference("")
  private val mavenLoaded = AtomicInteger(0)
  private val configsDiscovered = AtomicInteger(0)
  private val configsCacheHits = AtomicInteger(0)
  @volatile private var ticker: Thread | Null = null

  def start(interval: FiniteDuration = 15.seconds): Unit =
    if ticker != null then return
    startedAtMs.set(System.currentTimeMillis())
    phase.set("starting")
    detail.set("")
    mavenLoaded.set(0)
    configsDiscovered.set(0)
    configsCacheHits.set(0)
    val t = new Thread(
      () =>
        try
          while !Thread.currentThread().isInterrupted do
            Thread.sleep(interval.toMillis)
            report()
        catch case _: InterruptedException => ()
      ,
      "coordinator-progress"
    )
    t.setDaemon(true)
    ticker = t
    t.start()
    report()

  def stop(): Unit =
    val t = ticker
    ticker = null
    if t != null then
      t.interrupt()
      try t.join(1000)
      catch case _: InterruptedException => ()
    report()
    phase.set("idle")
    detail.set("")

  def setPhase(name: String, current: String = ""): Unit =
    phase.set(name)
    detail.set(current)

  def setDetail(current: String): Unit =
    detail.set(current)

  def mavenInfoLoaded(): Unit =
    mavenLoaded.incrementAndGet()

  def configDiscovered(): Unit =
    configsDiscovered.incrementAndGet()

  def configCacheHit(): Unit =
    configsCacheHits.incrementAndGet()

  private def report(): Unit =
    val elapsedSec = ((System.currentTimeMillis() - startedAtMs.get()).max(0L) / 1000L)
    val current = detail.get()
    val suffix = if current.nonEmpty then s" | $current" else ""
    Console.err.println(
      s"[progress] ${elapsedSec}s | ${phase.get()} | maven=${mavenLoaded.get()} configs=${configsDiscovered.get()} cacheHits=${configsCacheHits.get()}$suffix"
    )
