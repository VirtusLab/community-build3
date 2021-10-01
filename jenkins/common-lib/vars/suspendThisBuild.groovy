#!/usr/bin/env groovy

def call() {
  def jenkins = jenkins.model.Jenkins.instance
	def job = jenkins.getItemByFullName(currentBuild.fullProjectName)
	def build = job.getBuildByNumber(currentBuild.number)
	build.getExecution().pause(true)
}
