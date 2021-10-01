#!/usr/bin/env groovy

def call(String text) {
  def jsonSlurper = new groovy.json.JsonSlurperClassic()
  return jsonSlurper.parseText(text)
}
