#!/usr/bin/env groovy

def call(String jobName, String label) {
  def jenkins = jenkins.model.Jenkins.instance
  def job = jenkins.getItemByFullName(jobName)
  def builds = job.getBuilds()
  def trimmedLabel = label.trim()
 	def buildsWithLabel = builds.findAll { it.getDescription().trim() == trimmedLabel }
  if(!buildsWithLabel) return false
  else {
    def last = buildsWithLabel.last()
    return last && last.getResult()
  }
}
