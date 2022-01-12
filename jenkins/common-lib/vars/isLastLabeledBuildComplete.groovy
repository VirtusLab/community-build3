#!/usr/bin/env groovy

def call(String jobName, String label) {
  def jenkins = jenkins.model.Jenkins.instance
  def job = jenkins.getItemByFullName(jobName)
  def builds = job.getBuilds()
 	def lastLabeledBuild = builds.find { it.getDescription() == label }
  def buildResult = lastLabeledBuild?.getResult()
  
  // If is not yet complete, mark it as done when is in the last stage
  // This is done to prevent deadlocks when notifing downstream. 
  buildResult != Result.NOT_BUILT || isInNotifyStage(lastLabeledBuild) 
}

@NonCPS
def isInNotifyStage(hudson.model.Run run) {
  run
	  ?.getLog(10) // expected message is typically 9th last element in the log lines
	  ?.stream()
	  ?.anyMatch{ it.contains("(Notify downstream projects)") }
}
