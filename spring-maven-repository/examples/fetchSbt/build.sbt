val scala3Version = "3.0.0-RC1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "helloworld",
    organization := "hello",
    version := "0.1.0",
    scalaVersion := scala3Version,
    // resolvers += "Private Maven Repo" at "http://localhost/",
    libraryDependencies += "com.example" %% "greeter" % "1.0.1"
  )
