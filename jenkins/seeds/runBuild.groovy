// Look at initializeSeedJobs.groovy for how this file gets parameterized

def buildName

def buildPlanJobName = "/computeBuildPlan"
def buildPlanJobRef

def compilerJobName = "/buildCompiler"
def compilerJobRef

def communityProjectJobName = "/buildCommunityProject"

def mvnRepoUrl
def buildPlan
def compilerVersion

def inverseMultigraph(graph) {
    def inversed = [:]
    graph.each { k, vs -> inversed[k] = [] }
    graph.each { k, vs ->
        vs.each { v ->
            inversed[v] = inversed[v] + k
        }
    }
    return inversed
}

pipeline {
    agent none
    options {
      timeout(time: 8, unit: "HOURS")
    }
    stages {
        stage("Initialize build") {
            steps {
                script {
                    if (params.buildName) {
                        buildName = params.buildName
                    } else {
                        def now = new Date()
                        def dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd")
                        def formattedDate = dateFormat.format(now)
                        buildName = "${formattedDate}_${currentBuild.number}"
                    }
                    currentBuild.setDescription(buildName)
                    mvnRepoUrl = "${params.mvnRepoBaseUrl}/${buildName}"
                }
            }
        }
        stage("Compiler & build plan") {
            parallel {
                stage("Compute build plan") {
                    when {
                        expression {
                            params.precomputedBuildPlan == null || params.precomputedBuildPlan == ""
                        }
                    }
                    steps {
                        script {
                            buildPlanJobRef = build(
                                job: buildPlanJobName,
                                parameters: [
                                    string(name: "scalaBinaryVersionSeries", value: params.scalaBinaryVersionSeries),
                                    string(name: "minStarsCount", value: params.minStarsCount),
                                    string(name: "maxProjectsCount", value: params.maxProjectsCount),
                                    string(name: "requiredProjects", value: params.requiredProjects),
                                    text(name: "replacedProjects", value: params.replacedProjects)
                                ]
                            )
                        }
                    }
                }
                stage("Build compiler") {
                    when {
                        expression {
                            params.publishedScalaVersion == null || params.publishedScalaVersion == ""
                        }
                    }
                    steps {
                        script {
                            compilerJobRef = build(
                                job: compilerJobName,
                                parameters: [
                                    string(name: "scalaRepoUrl", value: params.scalaRepoUrl),
                                    string(name: "scalaRepoBranch", value: params.scalaRepoBranch),
                                    string(name: "mvnRepoUrl", value: mvnRepoUrl)
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("Collect build metadata") {
            agent any
            steps {
                script {
                    dir(pwd(tmp: true)) {
                        def buildPlanText
                        if (params.precomputedBuildPlan) {
                            buildPlanText = params.precomputedBuildPlan
                            echo "Using the precomputed build plan:\n\n${buildPlanText}"
                        } else {
                            copyArtifacts(
                                projectName: buildPlanJobName,
                                filter: "buildPlan.json",
                                selector: specific(buildNumber: "${buildPlanJobRef.getNumber()}")
                            )
                            buildPlanText = readFile("buildPlan.json")
                            echo "Using the computed build plan:\n\n${buildPlanText}"
                        }
                        buildPlan = parseJson(buildPlanText)

                        if (params.publishedScalaVersion) {
                            compilerVersion = params.publishedScalaVersion
                            echo "Using the previously published compiler: ${compilerVersion}"
                        } else {
                            copyArtifacts(
                                projectName: compilerJobName,
                                filter: "compilerMetadata.json",
                                selector: specific(buildNumber: "${compilerJobRef.getNumber()}")
                            )
                            def compilerMetadataText = readFile("compilerMetadata.json")
                            def compilerMetadata = parseJson(compilerMetadataText)
                            compilerVersion = compilerMetadata.publishedCompilerVersion
                            echo "Using the compiled compiler:\n\n${compilerMetadata}"
                        }

                        deleteDir()
                    }
                }
            }
        }
        stage("Build community projects") {
            steps {
                script {
                    def jobs = [:]

                    def projectDeps = buildPlan.collectEntries { project ->
                        [project.name, project.dependencies]
                    }
                    def inversedProjectDeps = inverseMultigraph(projectDeps)
                    def delayInSeconds = 0
                    def sortedProjects = projectsByNumberOfDependencies(buildPlan)
                    for(project in sortedProjects) {
                        def proj = project // capture value for closure
                        // Do not start all builds at the same time, it might lead to crashing Jenkins instance in a hard to recover manner
                        def delay = delayInSeconds
                        delayInSeconds += 5
                        jobs[proj.name] = {
                            build(
                                job: communityProjectJobName,
                                quietPeriod: delay,
                                parameters: [
                                    string(name: "buildName", value: buildName),
                                    string(name: "projectName", value: proj.name),
                                    string(name: "repoUrl", value: proj.repoUrl),
                                    string(name: "revision", value: proj.revision),
                                    string(name: "javaVersion", value: proj.config?.java?.version), 
                                    string(name: "scalaVersion", value: compilerVersion),
                                    string(name: "version", value: proj.version),
                                    string(name: "targets", value: proj.targets),
                                    string(name: "enforcedSbtVersion", value: params.enforcedSbtVersion),
                                    string(name: "mvnRepoUrl", value: mvnRepoUrl),
                                    string(name: "elasticSearchUrl", value: params.elasticSearchUrl),
                                    string(name: "elasticSearchUserName", value: params.elasticSearchUserName),
                                    string(name: "elasticSearchSecretName", value: params.elasticSearchSecretName),
                                    string(name: "upstreamProjects", value: proj.dependencies.join(",")),
                                    string(name: "downstreamProjects", value: inversedProjectDeps[proj.name].join(",")),
                                ]
                            )
                        }
                    }
                    parallel jobs
                }
            }
        }
    }
}

@NonCPS
static projectsByNumberOfDependencies(buildPlan) {
    buildPlan.sort { it.dependencies.size() }
}
