val scala3Version = "3.1.1"

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
      "org.json4s" %% "json4s-native" % "4.0.4",
      "org.json4s" %% "json4s-ext" % "4.0.4",
      "com.github.pureconfig" %% "pureconfig-core" % "0.17.1",
      "com.lihaoyi" %% "os-lib" % "0.8.0",
      "com.lihaoyi" %% "requests" % "0.7.0" 
    )
  )
