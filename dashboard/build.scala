//> using scala 3.9.0-RC1
//> using jvm 25
//> using options -Werror -Wunused:all -deprecation

// Web framework
//> using dep com.softwaremill.sttp.tapir::tapir-http4s-server:1.13.19
//> using dep com.softwaremill.sttp.tapir::tapir-json-circe:1.13.19
//> using dep com.softwaremill.sttp.tapir::tapir-swagger-ui-bundle:1.13.19
//> using dep org.http4s::http4s-ember-server:0.23.34
//> using dep org.http4s::http4s-ember-client:0.23.34
//> using dep org.http4s::http4s-dsl:0.23.34
//> using dep org.http4s::http4s-circe:0.23.34

// Elasticsearch client - http4s native backend
//> using dep nl.gn0s1s::elastic4s-client-http4s:9.3.0

// HTML templating
//> using dep com.lihaoyi::scalatags:0.13.1

// SQLite for user data - using Magnum for direct-style JDBC (works great with Loom)
//> using dep org.xerial:sqlite-jdbc:3.53.2.0
//> using dep com.augustnagro::magnum:1.3.1

// Authentication
//> using dep com.github.jwt-scala::jwt-circe:11.0.4

// Logging - scribe for Scala-native logging
//> using dep com.outr::scribe:3.19.0
//> using dep com.outr::scribe-cats:3.19.0
//> using dep com.outr::scribe-slf4j2:3.19.0

// Testing
//> using test.dep org.scalameta::munit:1.2.1
//> using test.dep org.scalameta::munit-scalacheck:1.2.0
