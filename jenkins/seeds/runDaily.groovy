import java.text.SimpleDateFormat

def buildPlan

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
                            sh "/build/compute-build-plan.sh ${params.scalaBinaryVersionSeries} ${params.minStarsCount} ${params.maxProjectsCount} '${params.requiredProjects}'"
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
        stage("Persist and trigger running build plan") {
            agent { label 'master' }
            steps { 
                script {
                    writeFile(file: "buildPlan.json", text: buildPlan)
                    archiveArtifacts(artifacts: "buildPlan.json")
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
                            scalaVersionToPublish: params.scalaVersionToPublish,
                            publishedScalaVersion: params.publishedScalaVersion,
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
