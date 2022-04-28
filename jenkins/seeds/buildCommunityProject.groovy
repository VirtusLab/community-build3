// Look at initializeSeedJobs.groovy for how this file gets parameterized
@Library(['camunda-community', 'pipeline-logparser']) _

def labeledProjectWasBuilt(String label) {
    return isLastLabeledBuildFinished("/buildCommunityProject", label)
}

def getBuildStatus() {
    return sh(
        script: "cat build-status.txt",
        returnStdout: true
    ).trim()
}

def parseCommaSeparated(String string) {
    return (string ?: "").split(",").findAll { !it.isEmpty() } as List
}

def upstreamProjects = parseCommaSeparated(params.upstreamProjects)
def downstreamProjects = parseCommaSeparated(params.downstreamProjects)
def projectConfig = parseJson(params.projectConfig ?: "{}")
def podMemoryRequestMb = Math.min(projectConfig?.memoryRequestMb ?: 2048, 8192).toString() + "M"

pipeline {
    agent none
    options {
      timeout(time: 16, unit: "HOURS")
      retry(count: 2) // count: 1 means no retry
    }
    stages {
        stage("Initialize build") {
            agent { label "default" }
            steps {
                script {
                    currentBuild.setDescription("${params.buildName} :: ${params.projectName}")
                }
            }
        }
        stage("Wait for dependencies") {
            // Because the builds of all community projects are started at the same time
            // we need to manually check if the builds of ALL the dependencies of this particular project actually finished (not necessarily without errors).
            // If some dependencies haven't finished running yet, we suspend the job and let it be resumed later by some other dependency's build job.
            steps {
                script {
                    def missingDependencies = upstreamProjects.collect()
                    while (!missingDependencies.isEmpty()) {
                        missingDependencies.removeAll { projectName ->  labeledProjectWasBuilt("${params.buildName} :: ${projectName}") }
                        if (!missingDependencies.isEmpty()) {
                            echo "Some dependencies haven't been built yet: ${missingDependencies.join(", ")}"
                            sleep time: 2 * missingDependencies.size() , unit: 'MINUTES'
                        }
                    }
                }
            }
        }

        stage("Build project") {
          steps {
            podTemplate(
              podRetention: never(),
              activeDeadlineSeconds: 60,
              yaml: """
                    apiVersion: v1
                    kind: Pod
                    metadata:
                      name: project-builder
                    spec:
                      volumes:
                      - name: mvn-repo-cert
                        configMap:
                          name: mvn-repo-cert
                      containers:
                      - name: project-builder
                        image: virtuslab/scala-community-build-project-builder:jdk${params.javaVersion?: 11}-v0.0.9
                        imagePullPolicy: IfNotPresent
                        volumeMounts:
                        - name: mvn-repo-cert
                          mountPath: /usr/local/share/ca-certificates/mvn-repo.crt
                          subPath: mvn-repo.crt
                          readOnly: true
                        lifecycle:
                          postStart:
                            exec:
                              command: ["update-ca-certificates"]
                        command:
                        - cat
                        tty: true
                        resources:
                          requests:
                            cpu: 250m
                            memory: ${podMemoryRequestMb}
                          limits:
                            cpu: 2
                            memory: 10G
                        priorityClassName: "jenkins-agent-priority"
                    """.stripIndent()){
                    timeout(time: 2, unit: "HOURS"){
                    conditionalRetry([
                      agentLabel: POD_LABEL,
                      runSteps: {
                        catchError(stageResult: 'FAILURE', catchInterruptions: false) {
                            container('project-builder') {
                                script {
                                  retryOnConnectionError {
                                    sh """
                                      echo "building and publishing ${params.projectName}"
                                      echo 'failure' > build-status.txt # Assume failure unless overwritten by a successful build
                                      touch build-logs.txt build-summary.txt
                                    """
                                    ansiColor('xterm') {
                                        sh """
                                            (/build/build-revision.sh \
                                                '${params.repoUrl}' \
                                                '${params.revision}' \
                                                '${params.scalaVersion}' \
                                                '${params.version}' \
                                                '${params.targets}' \
                                                '${params.mvnRepoUrl}' \
                                                '${params.enforcedSbtVersion}' \
                                                '${params.projectConfig}' 2>&1 | tee build-logs.txt) \
                                            && [ "\$(cat build-status.txt)" = success ]
                                        """
                                    }
                                  }
                                }
                            }
                        }
                      },
                      postAlways: {
                        archiveArtifacts(artifacts: "build-*.txt")
                        stash(name: "buildResults", includes: "build-*.txt")
                      }
                    ])
                  }
            }
          }
          post { 
            always { 
                script {
                  retryOnConnectionError {
                    for (projectName in downstreamProjects) {
                        resumeLastLabeledBuild("/buildCommunityProject", "${params.buildName} :: ${projectName}")
                    }
                  }
                }
            }
            failure {
              script {
                echo "Build failed, reproduce it locally using following command:"
                echo "scala-cli run https://raw.githubusercontent.com/VirtusLab/community-build3/master/cli/scb-cli.scala -- reproduce ${env.BUILD_NUMBER}"
              }
            }
          }
        }

        stage("Report build results") {
            steps {
              podTemplate(
                name: "build-reporter-${env.BUILD_NUMBER}",
                containers: [
                  containerTemplate(name: 'reporter', image: 'virtuslab/scala-community-build-project-builder:jdk11-v0.0.9', command: 'sleep', args: '15m')
                ],
                envVars: [
                  envVar(key: 'ELASTIC_USERNAME', value: params.elasticSearchUserName),
                  secretEnvVar(key: 'ELASTIC_PASSWORD', secretName: params.elasticSearchSecretName, secretKey: 'elastic'),
                ],
                resourceRequestMemory: '250M'
              ){
                conditionalRetry([
                  agentLabel: POD_LABEL,
                  runSteps: {
                    container('reporter') {
                      timeout(unit: 'MINUTES', time: 15) {
                        unstash("buildResults")
                        waitUntil {
                            script {
                              retryOnConnectionError{
                                def elasticCredentialsDefined = sh(script: 'echo $ELASTIC_PASSWORD', returnStdout: true).trim()
                                if (elasticCredentialsDefined) {
                                    def timestamp = java.time.LocalDateTime.now()
                                    def buildStatus = getBuildStatus()
                                    0 == sh (
                                      script: "/build/feed-elastic.sh '${params.elasticSearchUrl}' '${params.projectName}' '${buildStatus}' '${timestamp}' build-summary.txt build-logs.txt '${params.version}' '${params.scalaVersion}' '${params.buildName}' '${env.BUILD_URL}'",
                                      returnStatus: true
                                    )
                                } else true
                              }
                            }
                        }
                    }
                  }
                }
              ])
            }
          }
        }
  }
}

