import scala.annotation.tailrec
import scala.concurrent.duration._

object Git {
  enum Revision:
    case Branch(name: String)
    case Tag(version: String)
    case Commit(sha: String)
    def stringValue = this match
      case Branch(name) => name
      case Tag(version) => version
      case Commit(sha)  => sha
  end Revision

  def unshallowSinceDottyRelease(projectDir: os.Path): Unit =
    // unshallow commits done after release Scala 3.0.0
    os.proc("git", "fetch", s"--shallow-since=2021-05-13", "--quiet")
      .call(cwd = projectDir, check = false)
      .exitCode

  def fetchTags(projectDir: os.Path): Unit =
    os.proc("git", "fetch", "--tags", "--quiet")
      .call(cwd = projectDir, check = false)
      .exitCode

  def checkout(
      repoUrl: String,
      projectName: String,
      revision: Option[Revision],
      depth: Option[Int]
  ): Option[os.Path] = {
    val branchOpt = revision.flatMap {
      case Revision.Branch(name) => Some(s"--branch=$name")
      case _                     => None
    }
    val depthOpt = depth.map(s"--depth=" + _)

    @tailrec def tryClone[T](
        retries: Int,
        backoffSeconds: Int = 1
    ): Option[os.Path] = {
      val projectDir = os.temp.dir(prefix = s"repo-$projectName")
      val proc = os
        .proc(
          "git",
          "clone",
          repoUrl,
          projectDir,
          "--quiet",
          branchOpt,
          depthOpt
        )
        .call(stderr = os.Pipe, check = false, timeout = 10.minutes.toMillis)

      if proc.exitCode == 0 then Some(projectDir)
      else if retries > 0 then
        Console.err.println(
          s"Failed to clone $repoUrl at revision ${revision}, backoff ${backoffSeconds}s"
        )
        proc.err.lines().foreach(Console.err.println)
        Thread.sleep(backoffSeconds * 1000)
        tryClone(retries - 1, (backoffSeconds * 2).min(60))
      else
        Console.err.println(
          s"Failed to clone $repoUrl at revision ${revision}:"
        )
        proc.err.lines().foreach(Console.err.println)
        None
    }

    def checkoutRevision(projectDir: os.Path): Boolean = revision match {
      case None | Some(_: Revision.Branch)       => true // no need to checkout
      case Some(Revision.Tag("master" | "main")) => true
      case Some(revision: (Revision.Commit | Revision.Tag)) =>
        val rev = revision match
          case Revision.Commit(sha) =>
            unshallowSinceDottyRelease(projectDir)
            sha
          case Revision.Tag(tag) =>
            fetchTags(projectDir)
            s"tags/$tag"

        val proc = os
          .proc("git", "checkout", rev, "--quiet")
          .call(
            cwd = projectDir,
            check = false,
            timeout = 15.seconds.toMillis,
            mergeErrIntoOut = true
          )
        if (proc.exitCode != 0)
          System.err.println(
            s"Failed to checkout $repoUrl, revision $revision: " + proc.out
              .lines()
              .mkString
          )
        proc.exitCode == 0
    }

    tryClone(retries = 10).filter(checkoutRevision(_))
  }
}
