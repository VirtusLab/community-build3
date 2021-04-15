import java.text.SimpleDateFormat

def date = new Date();
def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
def dateString = dateFormat.format(date)

def scalaRepoUrl = "https://github.com/lampepfl/dotty.git"
scalaVersion = "3.0.0-RC2-cm1"
proxyHostname = "nginx-proxy"

def buildScalaCommand = "docker exec \${c.id} /build/build-revision.sh ${scalaRepoUrl} master ${scalaVersion} ${proxyHostname}"

def buildScalaJobScript = """
docker.image('communitybuild3/publish-scala').withRun("-it --network builds-network", "cat") { c ->
    echo 'building and publishing scala'
    sh "${buildScalaCommand}"
    echo '***********************'
    echo 'Done'
}
"""

def buildProjectCommand(Map project) {
  return """docker exec \${c.id} /build/build-revision.sh ${project.repoUrl} ${project.revision} ${scalaVersion} ${project.version} '${project.targets}' ${proxyHostname}"""
}

def buildProjectJobScript(Map project) {
  return """
docker.image('communitybuild3/executor').withRun("-it --network builds-network", "cat") { c ->
    echo 'building and publishing ${project.name}'
    sh "${buildProjectCommand(project)}"
    echo '***********************'
    echo 'Done'
}
"""
}

def projects = [
  [name: "munit", dependencies: ["scala"], repoUrl: "https://github.com/scalameta/munit.git", revision: "v0.7.22", version: "0.7.22-communityBuild", targets: "org.scalameta%munit-scalacheck org.scalameta%munit"]
]


folder("/daily")

def rootDirName = "/daily/${dateString}"

folder(rootDirName)

def scalaJobPath = "${rootDirName}/scala"

pipelineJob(scalaJobPath) {
  definition {
    cps {
      script(buildScalaJobScript)
    }
  }
}

queue(scalaJobPath)

for(project in projects) {
  def jobPath = "${rootDirName}/${project.name}"
  def upstreamJobPaths = project.dependencies.collect{ dep -> "${rootDirName}/${dep}" }.join(',')
  pipelineJob(jobPath){
    triggers {
      upstream(upstreamJobPaths, 'FAILURE')
	}
    definition {
      cps {
        script(buildProjectJobScript(project))
      }
    }
  }
}