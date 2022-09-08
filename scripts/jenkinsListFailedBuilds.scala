//> using scala "3.1.3"
//> using lib "com.lihaoyi::requests:0.7.0"
//> using lib "com.lihaoyi::ujson:2.0.0"

import scala.concurrent.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

val jenkinsEndpoint = "https://scala3.westeurope.cloudapp.azure.com"
def projectURL(id: BuildId) = s"$jenkinsEndpoint/job/buildCommunityProject/$id"

@main def fetchFromJenkins(buildId: Int) =
  val task =
    for
      FailedProjects(failed, aborted) <- getFailedProjectsIds(buildId)
      (failedInfo, abortedInfo) <- getProjectsInfo(failed) zip getProjectsInfo(aborted)
      _ = showNames("Failed")(failedInfo)
      _ = showNames("Aborted")(abortedInfo)
      _ = showProjectsListForRetry(failedInfo ++ abortedInfo)
    yield ()
  Await.ready(task, 1.minute)

case class ProjectInfo(name: String, id: BuildId)
def getProjectsInfo(projectIds: Iterable[BuildId]) = Future
  .traverse(projectIds.toSeq) { id =>
    for name <- getProjectBuildName(id)
    yield ProjectInfo(name, id)
  }

def showProjectsListForRetry(projectsInfo: Seq[ProjectInfo]) =
  import scala.util.chaining.scalaUtilChainingOps
  println("==============")
  println("Projects for retry build:")
  projectsInfo
    .map(_.name)
    .mkString(",")
    .pipe(println)

def showNames(label: String)(projectsInfo: Seq[ProjectInfo]) =
  println("----------------")
  println(s"$label [${projectsInfo.size}]")
  val longestName = projectsInfo.foldLeft(0)(_ max _.name.length())
  for ProjectInfo(name, id) <- projectsInfo.sortBy(_.name)
  do println(s"${name.padTo(longestName, ' ')}\t${projectURL(id)}")

type BuildId = Int
case class FailedProjects(failed: Set[BuildId], aborted: Set[BuildId])
def getFailedProjectsIds(buildId: Int) = Future {
  def runBuildCauses(runId: Int) =
    s"$jenkinsEndpoint/job/runBuild/$runId/api/json?tree=actions[causes[*]]"

  val response = requests.get(runBuildCauses(buildId))
  val json = ujson.read(response.data.toString)

  val StatusPattern =
    raw"buildCommunityProject #(\d+) completed with status (\w+).*".r // completed with status (\w+).*".r

  val failedProjectStatus = for
    action <- json("actions").arr
    if action.obj.contains("_class")
    if action("_class").str == "jenkins.model.InterruptedBuildAction"
    causes <- action("causes").arr
    if causes(
      "_class"
    ).str == "org.jenkinsci.plugins.workflow.support.steps.build.DownstreamFailureCause"
    description = causes("shortDescription").str
    StatusPattern(projectId, status) = description
  yield projectId.toInt -> status

  val groups = failedProjectStatus.groupMap(_._2)(_._1).mapValues(_.toSet)
  val aborted = groups.getOrElse("ABORTED", Set.empty)
  val failed = groups.getOrElse("FAILURE", Set.empty)
  FailedProjects(failed, aborted)
}

def getProjectBuildName(buildId: BuildId): Future[String] = Future {
  val response = requests.get(s"$jenkinsEndpoint/job/buildCommunityProject/$buildId/api/json")
  val json = ujson.read(response.data.toString)

  val optName = for
    action <- json("actions").arr
    if action.obj.contains("_class")
    if action("_class").str == "hudson.model.ParametersAction"
    parameter <- action("parameters").arr
    if parameter("name").str == "projectName"
    Array(org, repo) = parameter("value").str.split('_')
  yield s"$org/$repo"

  optName.headOption.getOrElse(s"Cannot fetch name for $buildId")
}
