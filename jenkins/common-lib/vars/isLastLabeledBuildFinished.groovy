#!/usr/bin/env groovy

def call(String jobName, String label) {
  def jenkins = jenkins.model.Jenkins.instance
  def job = jenkins.getItemByFullName(jobName)
  def builds = job.getBuilds()
 	def buildsWithLabel = builds.findAll { it.getDescription() == label }
  if(!buildsWithLabel) return false
  else {
    def last = buildsWithLabel.last()
    return last && last.getResult()
  }
}
