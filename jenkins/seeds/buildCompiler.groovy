// See runBuildPlan.groovy for the list of the job's parameters

pipeline {
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
                        sh "/build/build-revision.sh '${params.scalaRepoUrl}' ${params.scalaRepoBranch} '${params.localScalaVersion}' '${params.mvnRepoUrl}'"
                    }
                }
            }
        }
    }
}