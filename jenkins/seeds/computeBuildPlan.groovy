// Look at initializeSeedJobs.groovy for how this file gets parameterized

pipeline {
    options {
        timeout(time: 30, unit: "MINUTES")
    }
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
                            image: virtuslab/scala-community-build-coordinator:v0.0.3
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
                            sh """
                              echo 'computing the build plan'
                              cat << EOF > /tmp/replaced-projects.txt \n${params.replacedProjects}\nEOF
                              cat << EOF > /tmp/maintained-project-configs.conf \n${params.projectsConfig}\nEOF
                              cat << EOF > /tmp/projects-filters.txt \n${params.projectsFilters}\nEOF
                              /build/compute-build-plan.sh \
                               '${params.scalaBinaryVersionSeries}' \
                               '${params.minStarsCount}' \
                               '${params.maxProjectsCount}' \
                               '${params.requiredProjects}' \
                               /tmp/replaced-projects.txt \
                               /tmp/maintained-project-configs.conf \
                               /tmp/projects-filters.txt
                            """
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
            agent any
            steps {
                writeFile(file: "buildPlan.json", text: buildPlan)
                archiveArtifacts(artifacts: "buildPlan.json")
            }
        }
    }
}
