import groovy.json.JsonSlurper

def scalaRepoUrl = "https://github.com/lampepfl/dotty.git"
scalaVersionToPublish = {{scalaVersionToPublish}} // e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
buildId = {{buildId}} // e.g. 2021-05-23_1
mvnRepoUrl = {{mvnRepoUrl}} // e.g. https://mvn-repo:8081/maven2/2021-05-23_1
elasticUrl = {{elasticUrl}} // e.g. https://community-build-es-http:9200
elasticSecretName = {{elasticSecretName}} // e.g. community-build-es-elastic-user

dailiesRootPath = "/daily"
currentBuildRootPath = "/daily/${buildId}"

def projectPath(String projectName) {
    return "${currentBuildRootPath}/${projectName}"
}

def buildScalaJobScript = """
podTemplate(yaml: '''
  apiVersion: v1
  kind: Pod
  metadata:
    name: publish-scala
  spec:
    containers:
    - name: publish-scala
      image: communitybuild3/publish-scala
      imagePullPolicy: IfNotPresent
      command:
      - cat
      tty: true
      '''.stripIndent()) {
      node(POD_LABEL) {
        container('publish-scala') {
          ansiColor('xterm') {
            echo 'building and publishing scala'
            sh "/build/build-revision.sh '${scalaRepoUrl}' master '${scalaVersionToPublish}' '${mvnRepoUrl}'"
          }
        }
      }
  }
"""

// Because the job will be triggered when ANY of its upstream dependencies finishes its build.
// We need to manually check if ALL the upstream jobs actually finished (not necessarily without errors).
// If some dependencies haven't finished running yet, we abort the job and let it be triggered again by some other upstream job.
def buildProjectJobScript(Map project) {
    return """
import java.time.*

projectName = '${project.name}'
dependencies = ['${project.dependencies.join("','")}']
repoUrl = '${project.repoUrl}'
revision = '${project.revision}'
scalaVersion = '${scalaVersionToPublish}'
version = '${project.version}'
targets = '${project.targets}'

mvnRepoUrl = '${mvnRepoUrl}'
elasticUrl = '${elasticUrl}'

def wasBuilt(String projectName) {
    def status = lastBuildStatus("${currentBuildRootPath}/\${projectName}")
    return status in ['SUCCESS', 'FAILURE', 'UNSTABLE']
}

def allDependenciesWereBuilt = dependencies.every { wasBuilt(it) }

if(!allDependenciesWereBuilt) {
	currentBuild.result = 'ABORTED'
    error('Not all dependencies have been built yet')
}

def buildResult = "SUCCESS"
def restxt = ""
def logs = ""

podTemplate(yaml: '''
  apiVersion: v1
  kind: Pod
  metadata:
    name: executor
  spec:
    containers:
    - name: executor
      image: communitybuild3/executor
      imagePullPolicy: IfNotPresent
      command:
      - cat
      tty: true
      env:
      - name: ELASTIC_USERNAME
        value: elastic
      - name: ELASTIC_PASSWORD
        valueFrom:
          secretKeyRef:
            name: ${elasticSecretName}
            key: elastic
'''.stripIndent()) {
  node(POD_LABEL) {
    container('executor') {
      ansiColor('xterm') {
        echo "building and publishing \${projectName}"
        try {
          ansiColor('xterm') {
            sh "/build/build-revision.sh '\${repoUrl}' '\${revision}' '\${scalaVersion}' '\${version}' '\${targets}' '\${mvnRepoUrl}' 2>&1 | tee logs.txt"
          }
        } catch (err) {
          buildResult = "FAILURE"
        }
        archiveArtifacts(artifacts: "res.txt")
      }
      timestamp = LocalDateTime.now();
      sh "/build/feed-elastic.sh '\${elasticUrl}' '\${projectName}' '\${buildResult}' '\${timestamp}' res.txt logs.txt"
    }
  }
}
"""
}

////////////

// Prepare Jenkins directory structure
folder(dailiesRootPath)
folder(currentBuildRootPath)


// Prepare and schedule publishing scala
def scalaJobPath = projectPath("lampepfl_dotty")
pipelineJob(scalaJobPath) {
  definition {
    cps {
      script(buildScalaJobScript)
      sandbox()
    }
  }
}
queue(scalaJobPath)


// Prepare jobs for projects from the community build
// which will be then triggered by their dependencies
def jsonSlurper = new JsonSlurper()
def projectsFileText = readFileFromWorkspace("buildPlan.json")
def projects = jsonSlurper.parseText(projectsFileText)

for(project in projects) {
  def jobPath = projectPath(project.name)
  def upstreamJobPaths = project.dependencies.collect{ dep -> projectPath(dep) }.join(',')
  pipelineJob(jobPath){
    triggers {
      upstream(upstreamJobPaths, 'FAILURE')
	}
    definition {
      cps {
        script(buildProjectJobScript(project))
        sandbox()
      }
    }
  }
}
