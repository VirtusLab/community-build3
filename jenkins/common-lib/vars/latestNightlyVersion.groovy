#!/usr/bin/env groovy

def call(String text) {
  def pattern = ~"(?<=title=\")(?:.+-bin-\\d{8}-\\w{7}-NIGHTLY)(?=/\")"
  def url = "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/".toURL()
  def compilerVersion = (url.text =~ pattern).findAll().last()
  return compilerVersion
}
