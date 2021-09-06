import groovy.json.JsonSlurper

// Look at runDaily.groovy for how this file gets parameterized

dailiesRootPath = "/daily"
currentBuildRootPath = "/daily/${buildId}"
projectsRootPath = "${currentBuildRootPath}/projects"
compilerJobPath = "${currentBuildRootPath}/compiler"

def projectPath(String projectName) {
    return "${projectsRootPath}/${projectName}"
}

// Prepare Jenkins directory structure
folder(dailiesRootPath)
folder(currentBuildRootPath)
folder(projectsRootPath)

// Prepare and schedule publishing the scala compiler
pipelineJob(compilerJobPath) {
    definition {
        cps {
            script(readFileFromWorkspace('/var/jenkins_home/seeds/buildCompiler.groovy'))
            sandbox()
        }
    }
    parameters {
        stringParam("scalaRepoUrl", scalaRepoUrl)
        stringParam("scalaRepoBranch", scalaRepoBranch)
        stringParam("scalaVersionToPublish", scalaVersionToPublish)
        stringParam("publishedScalaVersion", publishedScalaVersion)
        stringParam("mvnRepoUrl", mvnRepoUrl)
    }
}
queue(compilerJobPath)


// Prepare jobs for projects from the community build
// which will be then triggered by their dependencies
def jsonSlurper = new JsonSlurper()
def projectsFileText = readFileFromWorkspace("buildPlan.json")
def projects = jsonSlurper.parseText(projectsFileText)
def scalaVersion = publishedScalaVersion ?: scalaVersionToPublish

for(project in projects) {
    def jobPath = projectPath(project.name)
    def upstreamProjectPaths = project.dependencies.collect{ dep -> projectPath(dep) }.join(',')
    def hasDependencies = !upstreamProjectPaths.isEmpty()
    def upstreamJobPaths = hasDependencies ? upstreamProjectPaths : compilerJobPath
    def triggerThreshold = hasDependencies ? 'FAILURE' : 'SUCCESS' // Don't try to build projects if the compiler failed to build
    pipelineJob(jobPath){
        triggers {
            upstream(upstreamJobPaths, triggerThreshold)
        }
        definition {
            cps {
                script(readFileFromWorkspace('/var/jenkins_home/seeds/buildCommunityProject.groovy'))
                sandbox()
            }
        }
        parameters {
            stringParam("projectName", project.name)
            stringParam("repoUrl", project.repoUrl)
            stringParam("revision", project.revision)
            stringParam("scalaVersion", scalaVersion)
            stringParam("version", project.version)
            stringParam("targets", project.targets)
            stringParam("dependencies", upstreamJobPaths)
            stringParam("mvnRepoUrl", mvnRepoUrl)
            stringParam("elasticUrl", elasticUrl)
            stringParam("elasticSecretName", elasticSecretName)
        }
    }
}