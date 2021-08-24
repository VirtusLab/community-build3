import java.time.LocalDateTime

def wasBuilt(String projectPath) {
    def status = lastBuildStatus(projectPath)
    return status in ['SUCCESS', 'FAILURE', 'UNSTABLE']
}

pipeline {
    parameters {
        //Keep parameters in sync with runBuildPlan.groovy
        string(name: "projectName")
        string(name: "repoUrl")
        string(name: "revision")
        string(name: "scalaVersion")
        string(name: "version")
        string(name: "targets")
        string(name: "dependencies")
        string(name: "mvnRepoUrl")
        string(name: "elasticUrl", defaultValue: "https://community-build-es-http:9200")
        string(name: "elasticSecretName", defaultValue: "community-build-es-elastic-user")
    }
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
        stage("Build project") {
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
            steps {
                container('executor') {
                    script {
                        echo "building and publishing ${params.projectName}"
                        def buildResult = "SUCCESS"
                        try {
                            ansiColor('xterm') {
                                sh """
                                    /build/build-revision.sh '${params.repoUrl}' '${params.revision}' '${params.scalaVersion}' '${params.version}' '${params.targets}' '${params.mvnRepoUrl}' 2>&1 | tee logs.txt
                                """
                            }
                        } catch (err) {
                            buildResult = "FAILURE"
                        }
                        def buildStatus = sh(
                            script: "cat build-status.txt",
                            returnStdout: true
                        ).trim()
                        if (buildStatus != "success") {
                            buildResult = "FAILURE"
                        }
                        archiveArtifacts(artifacts: "build-summary.txt")
                        timestamp = LocalDateTime.now();
                        if (env.ELASTIC_PASSWORD) {
                            sh "/build/feed-elastic.sh '${params.elasticUrl}' '${params.projectName}' '${buildResult}' '${timestamp}' build-summary.txt logs.txt"
                        }
                    }
                }
            }
        }
    }
}