#!/usr/bin/env groovy

def call(String jobName, String label) {
  def jenkins = jenkins.model.Jenkins.instance
  def job = jenkins.getItemByFullName(jobName)
  def builds = job.getBuilds()
 	def lastLabeledBuild = builds.find { it.getDescription() == label }
  if(lastLabeledBuild){
	  lastLabeledBuild.getExecution()?.pause(false)
    println("Resumed build `${label}`")
  } else {
    println("Cannot resume build with label '${label}' - not found")
  }
}
