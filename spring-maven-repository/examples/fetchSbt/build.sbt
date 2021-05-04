val scala3Version = "3.0.0-RC3"
connectInput in run := true

lazy val root = project
  .in(file("."))
  .settings(
    name := "helloworld",
    organization := "hello",
    version := "0.1.0",
    scalaVersion := scala3Version,
    resolvers += "Private Maven Repo" at s"http://localhost:8081/maven2",
    libraryDependencies += "com.example" %% "greeter" % "1.0.1"
  )
