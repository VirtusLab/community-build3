// Look at initializeSeedJobs.groovy for how this file gets parameterized

def labeledProjectWasBuilt(String label) {
    def status = getLastLabeledBuildStatus("/buildCommunityProject", label)
    return status in ['SUCCESS', 'FAILURE', 'UNSTABLE']
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

def elasticCredentialsDefined = false
def wasBuildSuccessful = false

pipeline {
    agent none
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
                            image: virtuslab/scala-community-build-project-builder:v0.0.1
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
                    steps {
                        // Set SUCCESS here for the entire build for now so that next stages are still executed
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            container('project-builder') {
                                script {
                                    echo "building and publishing ${params.projectName}"
                                    sh "echo 'failure' > build-status.txt" // Assume failure unless overwritten by a successful build
                                    sh "touch build-logs.txt build-summary.txt"
                                    elasticCredentialsDefined = sh(script: 'echo $ELASTIC_PASSWORD', returnStdout: true).trim()
                                    try {
                                        ansiColor('xterm') {
                                            sh """
                                                /build/build-revision.sh '${params.repoUrl}' '${params.revision}' '${params.scalaVersion}' '${params.version}' '${params.targets}' '${params.mvnRepoUrl}' '${params.enforcedSbtVersion}' 2>&1 | tee build-logs.txt
                                            """
                                        }
                                        wasBuildSuccessful = getBuildStatus() == "success"
                                        assert wasBuildSuccessful // Mark the entire build as failed if the build didn't explicitly succeed
                                    } finally {
                                        archiveArtifacts(artifacts: "build-logs.txt")
                                        archiveArtifacts(artifacts: "build-summary.txt")
                                        archiveArtifacts(artifacts: "build-status.txt")
                                    }
                                }
                            }
                        }
                        script {
                            // Set the status before the job actually finishes so that downstream builds know they can continue
                            if (wasBuildSuccessful) {
                                currentBuild.result = 'SUCCESS'
                            } else {
                                currentBuild.result = 'FAILURE'
                            }
                        }
                    }
                }
                stage("Report build results") {
                    when {
                        expression {
                            elasticCredentialsDefined
                        }
                    }
                    steps {
                        container('project-builder') {
                            script {
                                def timestamp = java.time.LocalDateTime.now()
                                def buildStatus = getBuildStatus()
                                sh "/build/feed-elastic.sh '${params.elasticSearchUrl}' '${params.projectName}' '${buildStatus}' '${timestamp}' build-summary.txt build-logs.txt"
                            }
                        }
                    }
                }
            }
        }
        stage("Notify downstream projects") {
            steps {
                script {
                    for (projectName in downstreamProjects) {
                        resumeLastLabeledBuild("/buildCommunityProject", "${params.buildName} :: ${projectName}")
                    }
                }
            }
        }
    }
}
