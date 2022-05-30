// Look at initializeSeedJobs.groovy for how this file gets parameterized

pipeline {
    options {
        timeout(time: 120, unit: "MINUTES")
        retry(3)
    }
    agent { label "default" }
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
                            image: virtuslab/scala-community-build-coordinator:v0.0.13
                            imagePullPolicy: IfNotPresent
                            command:
                            - cat
                            tty: true
                            resources:
                              requests:
                                memory: 4G
                        priorityClassName: "jenkins-agent-priority"
                    '''
                }
            }
            steps {
                container('coordinator') {
                    script {
                      retryOnConnectionError{
                        ansiColor('xterm') {
                            sh """
                              echo 'computing the build plan'
                              cat << EOF > /tmp/replaced-projects.txt \n${params.replacedProjects}\nEOF
                              cat << EOF > /tmp/maintained-project-configs.conf \n${params.projectsConfig}\nEOF
                              cat << EOF > /tmp/projects-filters.txt \n${params.projectsFilters}\nEOF
                              /build/compute-build-plan.sh \
                               '${params.scalaBinaryVersion}' \
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
        }
        stage("Persist build plan") {
            steps {
              retryOnConnectionError {
                writeFile(file: "buildPlan.json", text: buildPlan)
                archiveArtifacts(artifacts: "buildPlan.json")
              }
            }
        }
    }
}
