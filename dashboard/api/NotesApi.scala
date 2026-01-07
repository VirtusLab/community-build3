package dashboard.api

import cats.effect.IO
import java.time.Instant

import dashboard.core.*
import dashboard.data.{SqliteRepository, ProjectNote}

/** Service for managing project notes */
class NotesApi(sqliteRepo: SqliteRepository):

  /** Get all notes for a project */
  def getNotes(projectNameRaw: String): IO[Either[String, List[ProjectNote]]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        sqliteRepo.getNotes(projectName).map(Right(_))

  /** Get project-level notes (not tied to a specific build) */
  def getProjectLevelNotes(projectNameRaw: String): IO[Either[String, List[ProjectNote]]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        sqliteRepo.getProjectLevelNotes(projectName).map(Right(_))

  /** Get notes for a specific build */
  def getNotesByBuild(
      projectNameRaw: String,
      buildId: String
  ): IO[Either[String, List[ProjectNote]]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        sqliteRepo.getNotesByBuild(projectName, buildId).map(Right(_))

  /** Get all notes for a buildId across all projects, grouped by project name */
  def getAllNotesByBuildId(buildId: String): IO[Map[String, List[ProjectNote]]] =
    sqliteRepo.getAllNotesByBuildId(buildId).map: notes =>
      notes.groupBy(n => n.projectName: String)

  /** Add a new note (requires authenticated user) */
  def addNote(
      projectNameRaw: String,
      request: AddNoteRequest,
      githubUser: String
  ): IO[Either[String, ProjectNote]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        val note = ProjectNote(
          id = 0, // Will be set by database
          projectName = projectName,
          scalaVersion = request.scalaVersion,
          buildId = request.buildId,
          githubUser = githubUser,
          note = request.note,
          githubIssueUrl = request.githubIssueUrl,
          createdAt = Instant.now(),
          updatedAt = None
        )
        sqliteRepo
          .addNote(note)
          .map: id =>
            Right(note.copy(id = id))

  /** Update an existing note (only by the author) */
  def updateNote(
      projectNameRaw: String,
      noteId: Long,
      request: UpdateNoteRequest,
      githubUser: String
  ): IO[Either[String, ProjectNote]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        // First verify the note exists and belongs to the user
        sqliteRepo
          .getNotes(projectName)
          .flatMap: notes =>
            notes.find(_.id == noteId) match
              case None =>
                IO.pure(Left(s"Note not found: $noteId"))
              case Some(existing) if existing.githubUser != githubUser =>
                IO.pure(Left("You can only edit your own notes"))
              case Some(existing) =>
                sqliteRepo
                  .updateNote(noteId, request.note, request.githubIssueUrl)
                  .map: success =>
                    if success then
                      Right(
                        existing.copy(
                          note = request.note,
                          githubIssueUrl = request.githubIssueUrl,
                          updatedAt = Some(Instant.now())
                        )
                      )
                    else Left("Failed to update note")

  /** Delete a note (by author or users with edit permissions) */
  def deleteNote(
      projectNameRaw: String,
      noteId: Long,
      githubUser: String,
      canEdit: Boolean = false
  ): IO[Either[String, Unit]] =
    ProjectName(projectNameRaw) match
      case Left(error)        => IO.pure(Left(error))
      case Right(projectName) =>
        sqliteRepo
          .getNotes(projectName)
          .flatMap: notes =>
            notes.find(_.id == noteId) match
              case None =>
                IO.pure(Left(s"Note not found: $noteId"))
              case Some(existing) if !canEdit && existing.githubUser != githubUser =>
                IO.pure(Left("You can only delete your own notes"))
              case Some(_) =>
                sqliteRepo
                  .deleteNote(noteId)
                  .map: success =>
                    if success then Right(()) else Left("Failed to delete note")
