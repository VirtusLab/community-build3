val scala3Version = "3.0.0-RC1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "greeter",
    organization := "com.example",
    version := "1.0.1",
    scalaVersion := scala3Version,
    publishTo := Some(
      "Private Maven Repo" at s"http://localhost:8080/"
    )
  )
