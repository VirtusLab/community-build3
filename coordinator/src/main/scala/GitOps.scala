import scala.annotation.tailrec

object Git:
  enum Revision:
    case Branch(name: String)
    case Tag(version: String)
    case Commit(sha: String)
    def stringValue = this match
      case Branch(name) => name
      case Tag(version) => version
      case Commit(sha)  => sha
  end Revision

  def revisionFromCached(revision: String): Option[Revision] =
    if revision.isEmpty then None
    else if revision.length == 40 && revision.forall(isHexDigit) then Some(Revision.Commit(revision))
    else Some(Revision.Tag(revision))

  private def isHexDigit(c: Char): Boolean =
    c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  def unshallowSinceDottyRelease(projectDir: os.Path): Unit =
    // unshallow commits done after release Scala 3.0.0
    os.proc("git", "fetch", "--shallow-since=2021-05-13", "--quiet")
      .call(
        cwd = projectDir,
        check = false,
        mergeErrIntoOut = true,
        timeout = CoordinatorRuntime.gitFetchTimeout.toMillis
      )

  def fetchTags(projectDir: os.Path): Unit =
    os.proc("git", "fetch", "--tags", "--quiet")
      .call(
        cwd = projectDir,
        check = false,
        mergeErrIntoOut = true,
        timeout = CoordinatorRuntime.gitFetchTimeout.toMillis
      )

  def checkout(
      repoUrl: String,
      projectName: String,
      revision: Option[Revision],
      depth: Option[Int],
      checkoutWorktree: Boolean = true
  ): Option[os.Path] = {
    val branchOpt = revision.flatMap {
      case Revision.Branch(name) => Some(s"--branch=$name")
      case Revision.Tag(tag)     => Some(s"--branch=$tag")
      case _                     => None
    }
    val depthOpt = depth.map(s"--depth=" + _)

    @tailrec def tryClone(
        retries: Int,
        backoffSeconds: Int = 1
    ): Option[os.Path] = {
      val projectDir = os.temp.dir(prefix = s"repo-$projectName")
      val proc = CoordinatorRuntime.withPermit(CoordinatorRuntime.gitClone) {
        os
          .proc(
            "git",
            "clone",
            repoUrl,
            projectDir,
            "--quiet",
            "--no-checkout",
            branchOpt,
            depthOpt
          )
          .call(
            mergeErrIntoOut = true,
            check = false,
            timeout = CoordinatorRuntime.gitCloneTimeout.toMillis
          )
      }

      if proc.exitCode == 0 then Some(projectDir)
      else if retries > 0 then
        Console.err.println(
          s"Failed to clone $repoUrl at revision ${revision}, backoff ${backoffSeconds}s"
        )
        proc.out.lines().foreach(Console.err.println)
        os.remove.all(projectDir)
        Thread.sleep(backoffSeconds * 1000)
        tryClone(retries - 1, (backoffSeconds * 2).min(60))
      else
        Console.err.println(
          s"Failed to clone $repoUrl at revision ${revision}:"
        )
        proc.out.lines().foreach(Console.err.println)
        os.remove.all(projectDir)
        None
    }

    def checkoutRevision(projectDir: os.Path): Boolean =
      if !checkoutWorktree then true
      else {
        val rev = revision match
          case Some(Revision.Commit(sha)) =>
            unshallowSinceDottyRelease(projectDir)
            sha
          case Some(Revision.Tag(tag)) if tag != "master" && tag != "main" =>
            fetchTags(projectDir)
            tag
          case _ =>
            "HEAD"

        val proc = os
          .proc("git", "checkout", "--quiet", "--force", rev)
          .call(
            cwd = projectDir,
            check = false,
            mergeErrIntoOut = true,
            timeout = CoordinatorRuntime.gitCheckoutTimeout.toMillis
          )
        if proc.exitCode != 0 then
          System.err.println(
            s"Failed to checkout revision $revision: " + proc.out.lines().mkString
          )
        proc.exitCode == 0
      }

    tryClone(retries = 10).flatMap { projectDir =>
      if checkoutRevision(projectDir) then Some(projectDir)
      else
        os.remove.all(projectDir)
        None
    }
  }
end Git
