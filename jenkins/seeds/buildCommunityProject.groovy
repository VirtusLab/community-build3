// Look at initializeSeedJobs.groovy for how this file gets parameterized
@Library(['camunda-community', 'pipeline-logparser']) _

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
def requestedMemoryMb = projectConfig?.memoryRequestMb ?: 0
def podMemoryRequestMb = Math.min(
      // Additional 0.5GB buffer
      requestedMemoryMb > 0 ? requestedMemoryMb + 512 : 2048,
      8192
    ).toString() + "M"
def retryOnBuildFailureCount = 1
def retryOnBuildFailureMsg = "Enforcing retry of the build after failure."

pipeline {
    agent none
    stages {
        stage("Initialize build") {
            agent none
            steps {
                script {
                    currentBuild.setDescription("${params.buildName} :: ${params.projectName}")
                }
            }
        }

        stage("Build project") {
          steps {
            podTemplate(
              podRetention: never(),
              activeDeadlineSeconds: 300,
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
                  shareProcessNamespace: true
                  containers:
                  - name: project-builder
                    image: virtuslab/scala-community-build-project-builder:jdk${params.javaVersion?: 11}-v0.0.16
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
                    livenessProbe:
                      exec:
                        command: 
                        # Terminate container as soon as jnlp is terminated
                        # Without that jnlp may terminate leaving project-builder alive leading to infinite resource lock. 
                        - /bin/bash
                        - -c
                        - ps -ef | grep jenkins/agent.jar | grep -v grep
                      initialDelaySeconds: 60
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
                    """.stripIndent()
            ){
              conditionalRetry([
                agentLabel: POD_LABEL,
                customFailurePatterns: [
                  "manual-retry-trigger": ".*${retryOnBuildFailureMsg}.*"
                ],
                runSteps: {
                  container('project-builder') {
                    timeout(time: 90, unit: "MINUTES"){
                      script {
                        retryOnConnectionError {
                          sh """
                            echo "building and publishing ${params.projectName}"
                            echo 'failure' > build-status.txt # Assume failure unless overwritten by a successful build
                            touch build-logs.txt build-summary.txt
                          """
                          // Pre-stash in case of pipeline failure
                          stash(name: "buildResults", includes: "build-*.txt")
                          ansiColor('xterm') {
                            def status = sh(
                              returnStatus: true,
                              script: """
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
                            )
                            if(status != 0){
                              def extraMsg = ""
                              if(retryOnBuildFailureCount-- > 0) {
                                // Allow the run manager to catch known pattern to allow for retry
                                echo retryOnBuildFailureMsg
                                extraMsg = ", retrying to check stability"
                              }
                              throw new Exception("Project build failed with exit code ${status}${extraMsg}")
                            }
                          }
                        }
                      }
                    }
                  }
                },
                postAlways: {
                  retryOnConnectionError {
                    archiveArtifacts(artifacts: "build-*.txt")
                    stash(name: "buildResults", includes: "build-*.txt")
                  }
                }
              ])
            }
          }
          post { 
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
                containerTemplate(
                  name: 'reporter',
                  image: 'virtuslab/scala-community-build-project-builder:jdk11-v0.0.16',
                  command: 'sleep',
                  args: '15m',
                  resourceRequestMemory: '250M',
                  livenessProbe: containerLivenessProbe(
                    execArgs: '/bin/bash -c "ps -ef | grep jenkins/agent.jar | grep -v grep"',
                    initialDelaySeconds: 60
                  )
                )
              ],
              yaml: """
                kind: Pod
                spec:
                  shareProcessNamespace: true
                """,
              envVars: [
                envVar(key: 'ELASTIC_USERNAME', value: params.elasticSearchUserName),
                secretEnvVar(key: 'ELASTIC_PASSWORD', secretName: params.elasticSearchSecretName, secretKey: 'elastic'),
              ],
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

