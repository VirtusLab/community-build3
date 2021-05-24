import java.text.SimpleDateFormat

publishedScalaVersion = "3.0.0"
scalaVersionToPublish = "3.0.1-RC1-bin-COMMUNITY-SNAPSHOT"
minStarsCount = 100
maxProjectsCount = 40
requiredProjects = ""
mvnRepoBaseUrl = "http://mvn-repo:8081/maven2"

def buildPlan

podTemplate(yaml: '''
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
'''.stripIndent()) {
    node(POD_LABEL) {
        container('coordinator') {
            ansiColor('xterm') {
                echo 'computing the build plan'
                sh "/build/compute-build-plan.sh $publishedScalaVersion $minStarsCount $maxProjectsCount '$requiredProjects'"
            }
            buildPlan = sh(
                script: "cat /build/data/buildPlan.json",
                returnStdout: true
            )
        }
    }
}

node('master') {
    writeFile(file: "buildPlan.json", text: buildPlan)
    archiveArtifacts(artifacts: "buildPlan.json")
    def date = new Date();
    def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
    def dateString = dateFormat.format(date)
    def buildId = "${dateString}_${BUILD_NUMBER}"
    def mvnRepoUrl = "${mvnRepoBaseUrl}/${buildId}"
    def elasticUrl = "https://community-build-es-http:9200"
    def elasticSecretName = "community-build-es-elastic-user"
    def runBuildPlanTemplate = readFile('/var/jenkins_home/seeds/runBuildPlan.groovy')
    def runBuildPlanScript = runBuildPlanTemplate
        .replace("{{scalaVersionToPublish}}", "'$scalaVersionToPublish'")
        .replace("{{buildId}}", "'$buildId'")
        .replace("{{mvnRepoUrl}}", "'$mvnRepoUrl'")
        .replace("{{elasticUrl}}", "'$elasticUrl'")
        .replace("{{elasticSecretName}}", "'$elasticSecretName'")
    jobDsl(scriptText: runBuildPlanScript, sandbox: true)
}
