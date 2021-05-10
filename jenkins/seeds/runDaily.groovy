scalaVersion = "3.0.0-RC3"

node {
    def buildPlan
    docker.image('communitybuild3/coordinator').withRun("-it", "cat") { c ->
        sh "docker exec ${c.id} /build/compute-build-plan.sh $scalaVersion"
        buildPlan = sh(
            script: "docker exec ${c.id} cat /build/data/buildPlan.json",
            returnStdout: true
        )
    }
    writeFile(file: "buildPlan.json", text: buildPlan)
    archiveArtifacts(artifacts: "buildPlan.json")
    jobDsl(scriptText: readFile('/var/jenkins_home/seeds/runBuildPlan.groovy'))
}