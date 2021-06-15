#!/usr/bin/env groovy

def call(String jobName) {
  def jenkins = jenkins.model.Jenkins.instance
	def job = jenkins.getItemByFullName(jobName)
	def lastBuild = job.getLastBuild()
	if (lastBuild == null) {
		return null
	} else {
		return lastBuild.getResult().toString()
	}
}
