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
    agent { label "default" }
    options {
      timeout(time: 16, unit: "HOURS")
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
                        def optScalaVersion = params.publishedScalaVersion ? params.publishedScalaVersion + "_" : ""
                        buildName = "${optScalaVersion}${formattedDate}_${currentBuild.number}"
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
                                    string(name: "scalaBinaryVersion", value: params.scalaBinaryVersion),
                                    string(name: "minStarsCount", value: params.minStarsCount),
                                    string(name: "maxProjectsCount", value: params.maxProjectsCount),
                                    string(name: "requiredProjects", value: params.requiredProjects),
                                    text(name: "replacedProjects", value: params.replacedProjects),
                                    text(name: "projectsConfig", value: params.projectsConfig),
                                    text(name: "projectsFilters", value: params.projectsFilters) 
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
              buildPlan.eachWithIndex{ stagePlan, idx ->
                stage("Stage $idx"){
                  def stage = stagePlan // capture value for closure
                  def projectsInStage = stage.collect{ it.name }.join(", ")
                  echo "Running stage $idx/${buildPlan.size()}, projects [${stage.size()}]: ${projectsInStage}"
                  def jobs = [:]
                  def projectDeps = stage.collectEntries { project ->
                      [project.name, project.dependencies]
                  }
                  def inversedProjectDeps = inverseMultigraph(projectDeps)
                  catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                    for(project in stage) {
                        def proj = project // capture value for closure
                        def projectConfigJson = proj.config ? groovy.json.JsonOutput.toJson(proj.config) : "{}"
                        jobs[proj.name] = {
                            build(
                                job: communityProjectJobName,
                                parameters: [
                                    string(name: "buildName", value: buildName),
                                    string(name: "projectName", value: proj.name),
                                    string(name: "repoUrl", value: proj.repoUrl),
                                    string(name: "revision", value: proj.revision),
                                    string(name: "javaVersion", value: proj.config?.java?.version),
                                    string(name: "projectConfig", value: projectConfigJson), 
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
        }
    }
    post {
      always {
        podTemplate(
          containers: [
            // Any container having a curl or pre-installed scala-cli would work 
            containerTemplate(name: 'reporter', image: 'virtuslab/scala-community-build-project-builder:jdk17-v0.0.11', command: 'sleep', args: '15m'),
          ],
          envVars: [
            envVar(key: 'ELASTIC_USERNAME', value: params.elasticSearchUserName),
            secretEnvVar(key: 'ELASTIC_PASSWORD', secretName: params.elasticSearchSecretName, secretKey: 'elastic'),
          ],
          volumes: [
            configMapVolume(mountPath: '/build-scripts', configMapName: 'jenkins-build-scripts')
          ]
        ){
          node(POD_LABEL){
            container('reporter'){
              script {
                // Retry in case if failed to get the scala-cli
                retry(5){
                  retryOnConnectionError {
                    def reportFile = 'build-report.txt'
                    sh """
                      touch build-report.txt
                      scala-cli run /build-scripts/buildReport.scala --quiet \
                        -- "${params.elasticSearchUrl}" "${buildName}" "${reportFile}"
                      """
                    def report = readFile(reportFile)
                    echo "Build report: \n\n${report}"
                    archiveArtifacts(artifacts: reportFile)
                  }
                }
              }
            }
          }
        }
      }
    }
}
