val scala3Version = "3.0.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "scala3-community-build-coordinator",
    version := "0.1.0",

    scalaVersion := scala3Version,

    libraryDependencies ++= Seq(
      "com.novocode" % "junit-interface" % "0.11" % "test",
      // Newer versions of jsoup up to 1.13.1 are buggy and cause missing versions of artifacts
      "org.jsoup" % "jsoup" % "1.10.3",
      "com.google.code.gson" % "gson" % "2.8.6"
    )
  )
