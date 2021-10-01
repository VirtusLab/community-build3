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
                          name: publish-scala
                        spec:
                          containers:
                          - name: publish-scala
                            image: communitybuild3/publish-scala
                            imagePullPolicy: IfNotPresent
                            command:
                            - cat
                            tty: true
                    '''.stripIndent()
                }
            }
            steps {
                container('publish-scala') {
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
            agent { label 'master' }
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
