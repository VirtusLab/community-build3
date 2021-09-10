import java.text.SimpleDateFormat

String buildPlan
String localScalaVersion

// See job-seeds.yaml for the list of the job's parameters

pipeline {
    agent none
    stages {
        stage("Compute build plan") {
            when {
                beforeAgent true
                expression {
                    params.precomputedBuildPlan == null || params.precomputedBuildPlan == ""
                }
            }
            agent {
                kubernetes {
                    yaml '''
                        apiVersion: v1
                        kind: Pod
                        metadata:
                          name: coordinator
                        spec:
                          containers:
                          - name: coordinator
                            image: communitybuild3/coordinator
                            imagePullPolicy: IfNotPresent
                            command:
                            - cat
                            tty: true
                    '''
                }
            }
            steps {
                container('coordinator') {
                    script {
                        ansiColor('xterm') {
                            echo 'computing the build plan'
                            sh "cat << EOF > /tmp/replaced-projects.txt \n${params.replacedProjects}\nEOF"
                            sh "/build/compute-build-plan.sh '${params.scalaBinaryVersionSeries}' '${params.minStarsCount}' '${params.maxProjectsCount}' '${params.requiredProjects}' /tmp/replaced-projects.txt"
                        }
                        buildPlan = sh(
                            script: "cat /build/data/buildPlan.json",
                            returnStdout: true
                        )
                    }
                }
            }
        }
        stage("Use precomputed build plan") {
            when {
                beforeAgent true
                expression {
                    params.precomputedBuildPlan != null && params.precomputedBuildPlan != ""
                }
            }
            agent none
            steps {
                script {
                    buildPlan = params.precomputedBuildPlan
                }
            }
        }
        stage("Trigger build plan execution:") {
            agent { label 'master' }
            stages {
                stage("Persist build plan") {
                    steps {
                        writeFile(file: "buildPlan.json", text: buildPlan)
                        archiveArtifacts(artifacts: "buildPlan.json")
                    }
                }
                stage("Determine scala version") {
                    steps {
                        script {
                            if (!params.publishedScalaVersion) {
                                def tmpDirPath = "/tmp/compiler-repo"
                                sh(script: "git clone --depth 1 $scalaRepoUrl '$tmpDirPath'")
                                dir(tmpDirPath) {
                                    def baseVersion = sh(script: '''cat project/Build.scala | grep 'val baseVersion =' | xargs | awk '{ print $4 }' ''', returnStdout: true).trim()
                                    def commitHash = sh(script: '''git rev-parse HEAD''', returnStdout: true).trim()
                                    deleteDir()
                                    localScalaVersion = "${baseVersion}-bin-${commitHash}-COMMUNITY-BUILD"
                                }
                            }
                        }
                    }
                }
                stage("Spawn build jobs") {
                    steps {
                        script {
                            def date = new Date();
                            def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
                            def dateString = dateFormat.format(date)
                            def buildId = "${dateString}_${BUILD_NUMBER}"
                            def mvnRepoUrl = "${params.mvnRepoBaseUrl}/${buildId}"
                            def elasticUrl = params.elasticSearchUrl
                            def elasticSecretName = "community-build-es-elastic-user"
                            def runBuildPlanScript = sh(
                                script: "cat /var/jenkins_home/seeds/runBuildPlan.groovy",
                                returnStdout: true
                            )
                            jobDsl(
                                scriptText: runBuildPlanScript,
                                additionalParameters: [
                                    scalaRepoUrl: params.scalaRepoUrl,
                                    scalaRepoBranch: params.scalaRepoBranch,
                                    localScalaVersion: localScalaVersion,
                                    publishedScalaVersion: params.publishedScalaVersion,
                                    enforcedSbtVersion: params.enforcedSbtVersion,
                                    buildId: buildId,
                                    mvnRepoUrl: mvnRepoUrl,
                                    elasticUrl: elasticUrl,
                                    elasticSecretName: elasticSecretName
                                ],
                                sandbox: true
                            )
                        }
                    }
                }
            }
        }
    }
}
