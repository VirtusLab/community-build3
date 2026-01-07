package dashboard.data

import cats.effect.{IO, Resource}
import java.time.Instant
import javax.sql.DataSource
import org.sqlite.SQLiteDataSource
import com.augustnagro.magnum.*

import dashboard.SqliteConfig
import dashboard.core.*

/** Repository for user-generated data stored in SQLite */
trait SqliteRepository:
  /** Validate connectivity to SQLite */
  def validateConnection(): IO[Unit]

  /** Notes management */
  def addNote(note: ProjectNote): IO[Long]
  def updateNote(id: Long, note: String, issueUrl: Option[String]): IO[Boolean]
  def deleteNote(id: Long): IO[Boolean]
  def getNotes(projectName: ProjectName): IO[List[ProjectNote]]
  def getProjectLevelNotes(projectName: ProjectName): IO[List[ProjectNote]]
  def getNotesByBuild(projectName: ProjectName, buildId: String): IO[List[ProjectNote]]

  /** Get all notes for a specific buildId across all projects (for batch loading) */
  def getAllNotesByBuildId(buildId: String): IO[List[ProjectNote]]

  /** Failure signatures management */
  def saveSignature(signature: FailureSignature): IO[Unit]
  def getSignature(projectName: ProjectName, buildId: String): IO[Option[FailureSignature]]
  def findSimilarFailures(errorHash: String): IO[List[FailureSignature]]

/** User note on a project failure */
final case class ProjectNote(
    id: Long,
    projectName: ProjectName,
    scalaVersion: Option[String],
    buildId: Option[String],
    githubUser: String,
    note: String,
    githubIssueUrl: Option[String],
    createdAt: Instant,
    updatedAt: Option[Instant]
)

object SqliteRepository:

  def resource(config: SqliteConfig): Resource[IO, SqliteRepository] =
    Resource
      .make(IO.blocking {
        val ds = SQLiteDataSource()
        ds.setUrl(s"jdbc:sqlite:${config.path}")
        ds
      })(_ => IO.unit) // SQLite doesn't need explicit cleanup for the DataSource
      .evalTap(ds => IO.blocking(initializeSchema(ds)))
      .map(ds => SqliteRepositoryImpl(ds))

  private def initializeSchema(ds: DataSource): Unit =
    connect(ds):
      sql"""
        CREATE TABLE IF NOT EXISTS project_notes (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_name TEXT NOT NULL,
          scala_version TEXT,
          build_id TEXT,
          github_user TEXT NOT NULL,
          note TEXT NOT NULL,
          github_issue_url TEXT,
          created_at TEXT NOT NULL DEFAULT (datetime('now')),
          updated_at TEXT
        )
      """.update.run()

      sql"""
        CREATE TABLE IF NOT EXISTS failure_signatures (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_name TEXT NOT NULL,
          build_id TEXT NOT NULL,
          error_hash TEXT NOT NULL,
          error_summary TEXT,
          normalized_errors TEXT,
          UNIQUE(project_name, build_id)
        )
      """.update.run()

      sql"CREATE INDEX IF NOT EXISTS idx_notes_project ON project_notes(project_name)".update.run()
      sql"CREATE INDEX IF NOT EXISTS idx_notes_build ON project_notes(project_name, build_id)".update.run()
      sql"CREATE INDEX IF NOT EXISTS idx_signatures_hash ON failure_signatures(error_hash)".update.run()

  private class SqliteRepositoryImpl(ds: DataSource) extends SqliteRepository:

    override def validateConnection(): IO[Unit] =
      IO.blocking:
        connect(ds):
          val version = sql"SELECT sqlite_version()".query[String].run().headOption
          version match
            case Some(v) => scribe.info(s"SQLite connected: version $v")
            case None    => throw RuntimeException("Failed to query SQLite version")

    override def addNote(note: ProjectNote): IO[Long] =
      IO.blocking:
        connect(ds):
          val projectNameStr: String = note.projectName
          sql"""
            INSERT INTO project_notes (project_name, scala_version, build_id, github_user, note, github_issue_url, created_at)
            VALUES ($projectNameStr, ${note.scalaVersion}, ${note.buildId}, ${note.githubUser}, ${note.note}, ${note.githubIssueUrl}, ${note.createdAt.toString})
          """.update.run()
          // Get the last inserted row ID
          sql"SELECT last_insert_rowid()".query[Long].run().head

    override def updateNote(id: Long, note: String, issueUrl: Option[String]): IO[Boolean] =
      IO.blocking:
        connect(ds):
          sql"""
            UPDATE project_notes
            SET note = $note, github_issue_url = $issueUrl, updated_at = datetime('now')
            WHERE id = $id
          """.update.run() > 0

    override def deleteNote(id: Long): IO[Boolean] =
      IO.blocking:
        connect(ds):
          sql"DELETE FROM project_notes WHERE id = $id".update.run() > 0

    override def getNotes(projectName: ProjectName): IO[List[ProjectNote]] =
      IO.blocking:
        val projectNameStr: String = projectName
        connect(ds):
          sql"""
            SELECT id, project_name, scala_version, build_id, github_user, note, github_issue_url, created_at, updated_at
            FROM project_notes
            WHERE project_name = $projectNameStr
            ORDER BY created_at DESC
          """.query[ProjectNoteRow].run().toList.flatMap(_.toProjectNote)

    override def getProjectLevelNotes(projectName: ProjectName): IO[List[ProjectNote]] =
      IO.blocking:
        val projectNameStr: String = projectName
        connect(ds):
          sql"""
            SELECT id, project_name, scala_version, build_id, github_user, note, github_issue_url, created_at, updated_at
            FROM project_notes
            WHERE project_name = $projectNameStr AND build_id IS NULL
            ORDER BY created_at DESC
          """.query[ProjectNoteRow].run().toList.flatMap(_.toProjectNote)

    override def getNotesByBuild(projectName: ProjectName, buildId: String): IO[List[ProjectNote]] =
      IO.blocking:
        val projectNameStr: String = projectName
        connect(ds):
          sql"""
            SELECT id, project_name, scala_version, build_id, github_user, note, github_issue_url, created_at, updated_at
            FROM project_notes
            WHERE project_name = $projectNameStr AND build_id = $buildId
            ORDER BY created_at DESC
          """.query[ProjectNoteRow].run().toList.flatMap(_.toProjectNote)

    override def getAllNotesByBuildId(buildId: String): IO[List[ProjectNote]] =
      IO.blocking:
        connect(ds):
          sql"""
            SELECT id, project_name, scala_version, build_id, github_user, note, github_issue_url, created_at, updated_at
            FROM project_notes
            WHERE build_id = $buildId
            ORDER BY project_name, created_at DESC
          """.query[ProjectNoteRow].run().toList.flatMap(_.toProjectNote)

    override def saveSignature(signature: FailureSignature): IO[Unit] =
      IO.blocking:
        val projectNameStr: String = signature.projectName
        val normalizedJson = signature.normalizedErrors.mkString("\n")
        connect(ds):
          sql"""
            INSERT OR REPLACE INTO failure_signatures (project_name, build_id, error_hash, error_summary, normalized_errors)
            VALUES ($projectNameStr, ${signature.buildId}, ${signature.errorHash}, ${signature.errorSummary}, $normalizedJson)
          """.update.run()
          ()

    override def getSignature(projectName: ProjectName, buildId: String): IO[Option[FailureSignature]] =
      IO.blocking:
        val projectNameStr: String = projectName
        connect(ds):
          sql"""
            SELECT project_name, build_id, error_hash, error_summary, normalized_errors
            FROM failure_signatures
            WHERE project_name = $projectNameStr AND build_id = $buildId
          """.query[SignatureRow].run().headOption.flatMap(_.toFailureSignature)

    override def findSimilarFailures(errorHash: String): IO[List[FailureSignature]] =
      IO.blocking:
        connect(ds):
          sql"""
            SELECT project_name, build_id, error_hash, error_summary, normalized_errors
            FROM failure_signatures
            WHERE error_hash = $errorHash
          """.query[SignatureRow].run().toList.flatMap(_.toFailureSignature)

  // Row types for database mapping - derive DbCodec for reading from SQL
  private case class ProjectNoteRow(
      id: Long,
      projectName: String,
      scalaVersion: Option[String],
      buildId: Option[String],
      githubUser: String,
      note: String,
      githubIssueUrl: Option[String],
      createdAt: String,
      updatedAt: Option[String]
  ) derives DbCodec:
    def toProjectNote: Option[ProjectNote] =
      ProjectName(projectName).toOption.map: name =>
        ProjectNote(
          id = id,
          projectName = name,
          scalaVersion = scalaVersion,
          buildId = buildId,
          githubUser = githubUser,
          note = note,
          githubIssueUrl = githubIssueUrl,
          createdAt = Instant.parse(createdAt),
          updatedAt = updatedAt.map(Instant.parse)
        )

  private case class SignatureRow(
      projectName: String,
      buildId: String,
      errorHash: String,
      errorSummary: String,
      normalizedErrors: String
  ) derives DbCodec:
    def toFailureSignature: Option[FailureSignature] =
      ProjectName(projectName).toOption.map: name =>
        FailureSignature(
          projectName = name,
          buildId = buildId,
          errorHash = errorHash,
          errorSummary = errorSummary,
          normalizedErrors = normalizedErrors.split("\n").toList.filter(_.nonEmpty)
        )
