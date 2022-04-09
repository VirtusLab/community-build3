#!/usr/bin/env groovy

def call(String jobName, String label) {
  def jenkins = jenkins.model.Jenkins.instance
  def job = jenkins.getItemByFullName(jobName)
  def builds = job.getBuilds()
 	def build = builds.findAll { it.getDescription() == label }.last()
	return build && ( build.getResult() || build.getArtifacts().find { it.getFileName() == "build-status.txt" } )
}
