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
                            image: virtuslab/scala-community-build-coordinator:v0.1.2
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
                    retryOnConnectionError{
                      writeFile(file: "replaced-projects.txt", text: params.replacedProjects)
                      writeFile(file: "maintained-project-configs.conf", text: params.projectsConfig)
                      writeFile(file: "projects-filters.txt", text: params.projectsFilters)
                    }
                    script {
                      retryOnConnectionError{
                        ansiColor('xterm') {
                            sh """
                              /build/compute-build-plan.sh \
                               '${params.scalaBinaryVersion}' \
                               '${params.minStarsCount}' \
                               '${params.maxProjectsCount}' \
                               '${params.requiredProjects}' \
                               ${env.WORKSPACE}/replaced-projects.txt \
                               ${env.WORKSPACE}/maintained-project-configs.conf \
                               ${env.WORKSPACE}/projects-filters.txt
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
