val scala3Version = "3.0.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "bar",
    organization := "com.example",
    version := "0.0.1",
    scalaVersion := scala3Version,
    libraryDependencies += "com.example" %% "foo" % "0.0.1"
  )