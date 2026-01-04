package dashboard.data

import cats.effect.IO

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import dashboard.core.{
  BuildResult,
  ComparisonResult,
  FailureStreakInfo,
  ParsedLogs,
  ProjectHistory,
  ProjectName,
  ProjectsList
}

/** Generic in-memory cache with TTL */
class InMemoryCache[K, V](
    defaultTtl: FiniteDuration,
    maxSize: Int
):
  private case class CacheEntry(
      result: V,
      createdAt: Instant,
      ttl: FiniteDuration
  ):
    def isExpired(now: Instant): Boolean =
      now.isAfter(createdAt.plusMillis(ttl.toMillis))

  private val cache = new ConcurrentHashMap[K, CacheEntry]()

  def getOrCompute(key: K, compute: IO[V]): IO[V] =
    get(key).flatMap:
      case Some(result) => IO.pure(result)
      case None         =>
        compute.flatTap(result => put(key, result))

  def get(key: K): IO[Option[V]] =
    IO.delay:
      val now = Instant.now()
      Option(cache.get(key)).flatMap: entry =>
        if entry.isExpired(now) then
          cache.remove(key)
          None
        else Some(entry.result)

  def put(key: K, result: V): IO[Unit] =
    IO.delay:
      // Evict old entries if cache is too large
      if cache.size() >= maxSize then evictOldest()

      val entry = CacheEntry(result, Instant.now(), defaultTtl)
      cache.put(key, entry)
      ()

  /** Clear all entries from the cache */
  def clear(): IO[Int] =
    IO.delay:
      val size = cache.size()
      cache.clear()
      size

  /** Get current cache statistics */
  def stats: IO[CacheStats] =
    IO.delay:
      val now = Instant.now()
      val entries = cache.entrySet().asScala.toList
      val expired = entries.count(_.getValue.isExpired(now))
      CacheStats(
        size = entries.size,
        expiredCount = expired,
        activeCount = entries.size - expired
      )

  private def evictOldest(): Unit =
    val now = Instant.now()
    // First, remove expired entries
    cache.entrySet().removeIf(e => e.getValue.isExpired(now))

    // If still too large, remove oldest entries
    if cache.size() >= maxSize then
      val toRemove = cache
        .entrySet()
        .asScala
        .toList
        .sortBy(_.getValue.createdAt)
        .take(maxSize / 4)
      toRemove.foreach(e => cache.remove(e.getKey))

/** Cache statistics */
final case class CacheStats(
    size: Int,
    expiredCount: Int,
    activeCount: Int
)

/** Cache for comparison results to avoid re-querying Elasticsearch when filtering */
trait ComparisonCache:
  /** Get cached result or compute it */
  def getOrCompute(key: ComparisonCache.Key, compute: IO[ComparisonResult]): IO[ComparisonResult]

  /** Get cached result if available */
  def get(key: ComparisonCache.Key): IO[Option[ComparisonResult]]

  /** Store a result */
  def put(key: ComparisonCache.Key, result: ComparisonResult): IO[Unit]

  /** Clear all cached entries */
  def clear(): IO[Int]

  /** Get cache statistics */
  def stats: IO[CacheStats]

object ComparisonCache:
  /** Cache key based on comparison parameters */
  final case class Key(
      baseScalaVersion: Option[String],
      baseBuildId: Option[String],
      targetScalaVersion: Option[String],
      targetBuildId: Option[String]
  ):
    def isValid: Boolean =
      (baseScalaVersion.isDefined || baseBuildId.isDefined) &&
        (targetScalaVersion.isDefined || targetBuildId.isDefined)

  object Key:
    def fromParams(
        baseScalaVersion: Option[String],
        baseBuildId: Option[String],
        targetScalaVersion: Option[String],
        targetBuildId: Option[String]
    ): Key = Key(baseScalaVersion, baseBuildId, targetScalaVersion, targetBuildId)

  /** Create an in-memory cache with TTL */
  def inMemory(ttl: FiniteDuration = 30.minutes, maxSize: Int = 100): IO[ComparisonCache] =
    IO.delay:
      val underlying = new InMemoryCache[Key, ComparisonResult](ttl, maxSize)
      new ComparisonCache:
        override def getOrCompute(key: Key, compute: IO[ComparisonResult]): IO[ComparisonResult] =
          underlying.getOrCompute(key, compute)
        override def get(key: Key): IO[Option[ComparisonResult]] =
          underlying.get(key)
        override def put(key: Key, result: ComparisonResult): IO[Unit] =
          underlying.put(key, result)
        override def clear(): IO[Int] = underlying.clear()
        override def stats: IO[CacheStats] = underlying.stats

/** Cache for project history to avoid re-querying Elasticsearch when filtering */
trait HistoryCache:
  /** Get cached history or compute it */
  def getOrCompute(projectName: ProjectName, compute: IO[ProjectHistory]): IO[ProjectHistory]

  /** Clear all cached entries */
  def clear(): IO[Int]

  /** Get cache statistics */
  def stats: IO[CacheStats]

object HistoryCache:
  /** Create an in-memory cache with TTL */
  def inMemory(ttl: FiniteDuration = 30.minutes, maxSize: Int = 200): IO[HistoryCache] =
    IO.delay:
      val underlying = new InMemoryCache[ProjectName, ProjectHistory](ttl, maxSize)
      new HistoryCache:
        override def getOrCompute(projectName: ProjectName, compute: IO[ProjectHistory]): IO[ProjectHistory] =
          underlying.getOrCompute(projectName, compute)
        override def clear(): IO[Int] = underlying.clear()
        override def stats: IO[CacheStats] = underlying.stats

/** Cache for build results (by scala version or build ID) */
trait BuildsCache:
  /** Get cached builds or compute them */
  def getOrCompute(key: BuildsCache.Key, compute: IO[List[BuildResult]]): IO[List[BuildResult]]

  /** Clear all cached entries */
  def clear(): IO[Int]

  /** Get cache statistics */
  def stats: IO[CacheStats]

object BuildsCache:
  /** Cache key - either by scala version or by build ID */
  enum Key:
    case ByScalaVersion(version: String)
    case ByBuildId(buildId: String)
    case Latest

  /** Create an in-memory cache with TTL */
  def inMemory(ttl: FiniteDuration = 10.minutes, maxSize: Int = 50): IO[BuildsCache] =
    IO.delay:
      val underlying = new InMemoryCache[Key, List[BuildResult]](ttl, maxSize)
      new BuildsCache:
        override def getOrCompute(key: Key, compute: IO[List[BuildResult]]): IO[List[BuildResult]] =
          underlying.getOrCompute(key, compute)
        override def clear(): IO[Int] = underlying.clear()
        override def stats: IO[CacheStats] = underlying.stats

/** Cache for build logs - longer TTL since logs are stable once stored */
trait LogsCache:
  /** Get cached logs or compute them */
  def getOrCompute(key: LogsCache.Key, compute: IO[ParsedLogs]): IO[ParsedLogs]

  /** Clear all cached entries */
  def clear(): IO[Int]

  /** Get cache statistics */
  def stats: IO[CacheStats]

object LogsCache:
  /** Cache key - project name + build ID */
  final case class Key(projectName: String, buildId: String)

  /** Create an in-memory cache with TTL - logs are stable so use longer TTL */
  def inMemory(ttl: FiniteDuration = 2.hours, maxSize: Int = 100): IO[LogsCache] =
    IO.delay:
      val underlying = new InMemoryCache[Key, ParsedLogs](ttl, maxSize)
      new LogsCache:
        override def getOrCompute(key: Key, compute: IO[ParsedLogs]): IO[ParsedLogs] =
          underlying.getOrCompute(key, compute)
        override def clear(): IO[Int] = underlying.clear()
        override def stats: IO[CacheStats] = underlying.stats

/** Cache for all projects list */
trait ProjectsCache:
  /** Get cached projects list or compute it */
  def getOrCompute(compute: IO[ProjectsList]): IO[ProjectsList]

  /** Clear the cache */
  def clear(): IO[Int]

  /** Get cache statistics */
  def stats: IO[CacheStats]

object ProjectsCache:
  /** Create an in-memory cache with TTL */
  def inMemory(ttl: FiniteDuration = 15.minutes): IO[ProjectsCache] =
    IO.delay:
      // Single-entry cache (just use Unit as key)
      val underlying = new InMemoryCache[Unit, ProjectsList](ttl, 1)
      new ProjectsCache:
        override def getOrCompute(compute: IO[ProjectsList]): IO[ProjectsList] =
          underlying.getOrCompute((), compute)
        override def clear(): IO[Int] = underlying.clear()
        override def stats: IO[CacheStats] = underlying.stats

/** Cache for failure streaks (info per project) - expensive to compute */
trait FailureStreaksCache:
  /** Get cached failure streaks or compute them */
  def getOrCompute(
      key: FailureStreaksCache.Key,
      compute: IO[Map[ProjectName, FailureStreakInfo]]
  ): IO[Map[ProjectName, FailureStreakInfo]]

  /** Clear all cached entries */
  def clear(): IO[Int]

  /** Get cache statistics */
  def stats: IO[CacheStats]

object FailureStreaksCache:
  /** Cache key - by scala version or build ID */
  final case class Key(scalaVersion: Option[String], buildId: Option[String])

  /** Create an in-memory cache with TTL */
  def inMemory(ttl: FiniteDuration = 15.minutes, maxSize: Int = 50): IO[FailureStreaksCache] =
    IO.delay:
      val underlying = new InMemoryCache[Key, Map[ProjectName, FailureStreakInfo]](ttl, maxSize)
      new FailureStreaksCache:
        override def getOrCompute(
            key: Key,
            compute: IO[Map[ProjectName, FailureStreakInfo]]
        ): IO[Map[ProjectName, FailureStreakInfo]] =
          underlying.getOrCompute(key, compute)
        override def clear(): IO[Int] = underlying.clear()
        override def stats: IO[CacheStats] = underlying.stats

/** Aggregated cache manager for clearing all caches */
trait CacheManager:
  /** Clear all caches and return total cleared entries */
  def clearAll(): IO[ClearAllResult]

  /** Get statistics for all caches */
  def allStats: IO[AllCacheStats]

final case class ClearAllResult(
    comparisons: Int,
    history: Int,
    builds: Int,
    logs: Int,
    projects: Int,
    failureStreaks: Int
):
  def total: Int = comparisons + history + builds + logs + projects + failureStreaks

final case class AllCacheStats(
    comparisons: CacheStats,
    history: CacheStats,
    builds: CacheStats,
    logs: CacheStats,
    projects: CacheStats,
    failureStreaks: CacheStats
)

object CacheManager:
  def apply(
      comparisonCache: ComparisonCache,
      historyCache: HistoryCache,
      buildsCache: BuildsCache,
      logsCache: LogsCache,
      projectsCache: ProjectsCache,
      failureStreaksCache: FailureStreaksCache
  ): CacheManager = new CacheManager:
    override def clearAll(): IO[ClearAllResult] =
      for
        c <- comparisonCache.clear()
        h <- historyCache.clear()
        b <- buildsCache.clear()
        l <- logsCache.clear()
        p <- projectsCache.clear()
        f <- failureStreaksCache.clear()
      yield ClearAllResult(c, h, b, l, p, f)

    override def allStats: IO[AllCacheStats] =
      for
        c <- comparisonCache.stats
        h <- historyCache.stats
        b <- buildsCache.stats
        l <- logsCache.stats
        p <- projectsCache.stats
        f <- failureStreaksCache.stats
      yield AllCacheStats(c, h, b, l, p, f)
