pipeline {
    parameters {
        //Keep parameters in sync with runBuildPlan.groovy
        string(name: "scalaRepoUrl", defaultValue: "https://github.com/lampepfl/dotty.git")
        string(name: "scalaRepoBranch", defaultValue: "master")
        string(name: "scalaVersionToPublish") // e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
        string(name: "publishedScalaVersion") // e.g. 3.0.1
        string(name: "mvnRepoUrl") // e.g. https://mvn-repo:8081/maven2/2021-05-23_1
    }
    agent none
    stages {
        stage("Build scala") {
            when {
                beforeAgent true
                expression {
                    params.publishedScalaVersion == null | params.publishedScalaVersion == ""
                }
            }
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
                        sh "/build/build-revision.sh '${params.scalaRepoUrl}' ${params.scalaRepoBranch} '${params.scalaVersionToPublish}' '${params.mvnRepoUrl}'"
                    }
                }
            }
        }
    }
}