def wasBuilt(String projectPath) {
    def status = lastBuildStatus(projectPath)
    return status in ['SUCCESS', 'FAILURE', 'UNSTABLE']
}

def getBuildStatus() {
    return sh(
        script: "cat build-status.txt",
        returnStdout: true
    ).trim()
}

def elasticCredentialsDefined = false

// See runBuildPlan.groovy for the list of the job's parameters

pipeline {
    agent none
    stages {
        stage("Assert dependencies have been built") {
            // Because the job will be triggered when ANY of its upstream dependencies finishes its build.
            // We need to manually check if ALL the upstream jobs actually finished (not necessarily without errors).
            // If some dependencies haven't finished running yet, we abort the job and let it be triggered again by some other upstream job.
            when {
                expression {
                    params.dependencies.split(",").any { !wasBuilt(it) }
                }
            }
            agent none
            steps {
                script {
                    currentBuild.result = 'ABORTED'
                    error('Not all dependencies have been built yet')
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
                                  name: ${params.elasticSecretName}
                                  key: elastic
                                  optional: true
                    """.stripIndent()
                }
            }
            stages {
                stage("Build project") {
                    steps {
                        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                            container('executor') {
                                script {
                                    echo "building and publishing ${params.projectName}"
                                    sh "echo 'failure' > build-status.txt" // Assume failure unless overwritten by a successful build
                                    sh "touch build-logs.txt build-summary.txt"
                                    elasticCredentialsDefined = sh(script: 'echo $ELASTIC_PASSWORD', returnStdout: true).trim()
                                    try {
                                        ansiColor('xterm') {
                                            sh """
                                                /build/build-revision.sh '${params.repoUrl}' '${params.revision}' '${params.scalaVersion}' '${params.version}' '${params.targets}' '${params.mvnRepoUrl}' 2>&1 | tee build-logs.txt
                                            """
                                        }
                                        assert getBuildStatus() == "success"
                                    } finally {
                                        archiveArtifacts(artifacts: "build-logs.txt")
                                        archiveArtifacts(artifacts: "build-summary.txt")
                                        archiveArtifacts(artifacts: "build-status.txt")
                                    }
                                }
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
                        container('executor') {
                            script {
                                def timestamp = java.time.LocalDateTime.now()
                                def buildStatus = getBuildStatus()
                                sh "/build/feed-elastic.sh '${params.elasticUrl}' '${params.projectName}' '${buildStatus}' '${timestamp}' build-summary.txt build-logs.txt"
                            }
                        }
                    }
                }
            }
        }
    }
}
