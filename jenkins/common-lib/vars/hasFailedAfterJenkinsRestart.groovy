#!/usr/bin/env groovy

def call(String jobName, String label, String retryOnRestartMessage, String retryOnFailureMessage){
  def log = jenkins.model.Jenkins.instance
    ?.getItemByFullName(jobName)
    ?.getBuilds()
    ?.findAll { it.getDescription() == label }
    ?.last()
    ?.getLog()

  if (!log) false
  else {
    def sinceLastRetryIndex = log.indexOf(retryOnRestartMessage)
    if (log && sinceLastRetryIndex > 0) {
      log = log.substring(sinceLastRetryIndex)
    }

    def sinceLastFailureIndex = log.indexOf(retryOnFailureMessage)
    if (log && sinceLastFailureIndex > 0) {
      log = log.substring(sinceLastFailureIndex)
    }
    log && log.contains("after Jenkins restart") // Jenkins instance was restarted, might lead to build failure
  }
}