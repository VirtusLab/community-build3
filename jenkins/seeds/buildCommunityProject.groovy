// Look at initializeSeedJobs.groovy for how this file gets parameterized

def labeledProjectWasBuilt(String label) {
    def status = getLastLabeledBuildStatus("/buildCommunityProject", label)
    return status in ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']
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

pipeline {
    agent none
    options {
      timeout(time: 8, unit: "HOURS")
    } 
    stages {
        stage("Initialize build") {
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
                            suspendThisBuild()
                        }
                    }
                }
            }
        }
        stage("Prepare executor") {
            agent {
                kubernetes {
                    yaml """
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
                            image: virtuslab/scala-community-build-project-builder:jdk${params.javaVersion?: 11}-v0.0.6
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
                                memory: 5Gi
                              limits:
                                memory: 7Gi
                            env:
                            - name: ELASTIC_USERNAME
                              value: ${params.elasticSearchUserName}
                            - name: ELASTIC_PASSWORD
                              valueFrom:
                                secretKeyRef:
                                  name: ${params.elasticSearchSecretName}
                                  key: elastic
                                  optional: true
                    """.stripIndent()
                }
            }
            stages {
                stage("Build project") {
                    options {
                      timeout(time: 2, unit: "HOURS")
                    } 
                    steps {
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
                    }
                }
                stage("Report build results") {
                    steps {
                      container('project-builder') {
                        timeout(unit: 'MINUTES', time: 10) {
                            archiveArtifacts(artifacts: "build-logs.txt")
                            archiveArtifacts(artifacts: "build-summary.txt")
                            archiveArtifacts(artifacts: "build-status.txt")
                            waitUntil {
                                script {
                                  retryOnConnectionError{
                                    def elasticCredentialsDefined = sh(script: 'echo $ELASTIC_PASSWORD', returnStdout: true).trim()
                                    if (elasticCredentialsDefined) {
                                        def timestamp = java.time.LocalDateTime.now()
                                        def buildStatus = getBuildStatus()
                                        0 == sh (
                                          script: "/build/feed-elastic.sh '${params.elasticSearchUrl}' '${params.projectName}' '${buildStatus}' '${timestamp}' build-summary.txt build-logs.txt '${params.version}' '${params.scalaVersion}' '${params.buildName}'",
                                          returnStatus: true
                                        )
                                    } else true
                                  }
                                }
                            }
                        }
                      }
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
    }
}

def retryOnConnectionError(Closure body, int retries = 50, int delayBeforeRetry = 1){
  try {
    return body()
  } catch(io.fabric8.kubernetes.client.KubernetesClientException ex) {
    if(retries > 0) {
      sleep(delayBeforeRetry) // seconds
      return retryOnConnectionError(body, retries - 1, Math.min(15, delayBeforeRetry * 2))
    } else throw ex
  }
}
