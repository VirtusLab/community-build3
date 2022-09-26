// Look at initializeSeedJobs.groovy for how this file gets parameterized

def runBuildJobName = "/runBuild"
def compilerVersion
def buildName

pipeline {
  agent { label "default" }
  stages {
    stage("Initialize build") {
      steps {
        script {
          compilerVersion = latestNightlyVersion()
          currentBuild.setDescription(compilerVersion)
          buildName = "${compilerVersion}_weekly-${currentBuild.number}"
        }
      }
    }
    stage("Run build") {
      steps {
        script {
          runBuildJobRef = build(
            job: runBuildJobName,
            parameters: [
              string(name: "buildName", value: buildName)
              string(name: "publishedScalaVersion", value: compilerVersion),
              string(name: "minStarsCount", value: "-1"),
              string(name: "maxProjectsCount", value: "-1") // unlimited
            ]
          )
        }
      }
    }
  }
}
