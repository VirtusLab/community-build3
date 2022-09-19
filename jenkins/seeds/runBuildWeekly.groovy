// Look at initializeSeedJobs.groovy for how this file gets parameterized

def runBuildJobName = "/runBuild"
def compilerVersion

pipeline {
  agent { label "default" }
  stages {
    stage("Initialize build") {
      steps {
        script {
          compilerVersion = latestNightlyVersion()
          currentBuild.setDescription(compilerVersion)
        }
      }
    }
    stage("Run build") {
      steps {
        script {
          runBuildJobRef = build(
            job: runBuildJobName,
            parameters: [
              string(name: "publishedScalaVersion", value: compilerVersion),
              string(name: "minStarsCount", value: "0"),
              string(name: "maxProjectsCount", value: "-1") // unlimited
            ]
          )
        }
      }
    }
  }
}
