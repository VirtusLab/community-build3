// Look at initializeSeedJobs.groovy for how this file gets parameterized

import groovy.json.JsonOutput

def commitHash
def publishedCompilerVersion

pipeline {
    agent none
    stages {
        stage("Initialize build") {
            steps {
                script {
                    currentBuild.setDescription(params.buildName)
                }
            }
        }
        stage("Build compiler") {
            agent {
                kubernetes {
                    yaml '''
                        apiVersion: v1
                        kind: Pod
                        metadata:
                          name: compiler-builder
                        spec:
                          volumes:
                          - name: mvn-repo-cert
                            configMap:
                              name: mvn-repo-cert
                          containers:
                          - name: compiler-builder
                            image: virtuslab/scala-community-build-compiler-builder:v0.0.1
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
                    '''.stripIndent()
                }
            }
            steps {
                container('compiler-builder') {
                    ansiColor('xterm') {
                        echo 'building and publishing scala'
                        sh "/build/checkout.sh '${params.scalaRepoUrl}' '${params.scalaRepoBranch}' repo"
                        dir('repo') {
                            script {
                                def baseVersion = sh(script: '''cat project/Build.scala | grep 'val baseVersion =' | xargs | awk '{ print \$4 }' ''', returnStdout: true).trim()
                                commitHash = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
                                publishedCompilerVersion = "${baseVersion}-bin-${commitHash}-COMMUNITY-BUILD"
                            }
                        }
                        sh "/build/build.sh repo '${publishedCompilerVersion}' '${params.mvnRepoUrl}'"
                    }
                }
            }
        }
        stage("Persist build metadata") {
            agent any
            steps {
                script {
                    def metadata = [
                        commitHash: commitHash,
                        publishedCompilerVersion: publishedCompilerVersion
                    ]
                    writeFile(file: "compilerMetadata.json", text: JsonOutput.toJson(metadata))
                    archiveArtifacts(artifacts: "compilerMetadata.json")
                }
            }
        }
    }
}
