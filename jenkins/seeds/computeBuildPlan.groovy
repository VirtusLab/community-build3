// Look at initializeSeedJobs.groovy for how this file gets parameterized

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
        stage("Compute build plan") {
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
                            image: virtuslab/scala-community-build-coordinator:v0.0.1
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
        stage("Persist build plan") {
            agent { label 'master' }
            steps {
                writeFile(file: "buildPlan.json", text: buildPlan)
                archiveArtifacts(artifacts: "buildPlan.json")
            }
        }
    }
}
