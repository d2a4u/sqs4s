import sbt.Keys.organization
import sbt.addCompilerPlugin

val catsVersion = "2.5.0"
val catsEffectVersion = "2.4.1"
val circeVersion = "0.13.0"
val fs2Version = "2.5.4"
val http4sVersion = "0.21.22"
val log4catsVersion = "1.2.2"
val logbackVersion = "1.2.3"


lazy val dependencies = Seq(
  "io.circe" %% "circe-core" % circeVersion,
  "org.http4s" %% "http4s-core" % http4sVersion,
  "org.http4s" %% "http4s-client" % http4sVersion,
  "org.http4s" %% "http4s-scala-xml" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.typelevel" %% "cats-core" % catsVersion,
  "org.typelevel" %% "cats-effect" % catsEffectVersion,
  "org.typelevel" %% "log4cats-core" % log4catsVersion,
  "co.fs2" %% "fs2-core" % fs2Version,
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.3",
  "org.scala-lang.modules" %% "scala-xml" % "1.3.0",
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359"
)

lazy val testDependencies = Seq(
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,
  "org.scalatest" %% "scalatest" % "3.2.0",
  "org.scalacheck" %% "scalacheck" % "1.14.3",
)

lazy val commonSettings = Seq(
  organization in ThisBuild := "io.sqs4s",
  scalaVersion := "2.13.5",
  crossScalaVersions := Seq("2.12.13", "2.13.5"),
  parallelExecution in Test := false,
  scalafmtOnCompile := true,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  bintrayRepository := "sqs4s",
  Test / publishArtifact := false,
  pomIncludeRepository := { _ => false },
  releaseCrossBuild := true,
  bintrayReleaseOnPublish := false,
  addCompilerPlugin(
    "org.typelevel" % "kind-projector" % "0.11.3" cross CrossVersion.full
  )
)

lazy val ItTest = config("it").extend(Test)
lazy val native = project
  .in(file("native"))
  .configs(ItTest)
  .settings(
    inConfig(ItTest)(Defaults.testSettings),
    ItTest / testOptions += Tests.Argument("-oD")
  )
  .settings(
    name := "sqs4s-native",
    libraryDependencies ++= dependencies ++ testDependencies.map(_ % "it,test"),
    Compile / scalacOptions ~= filterConsoleScalacOptions,
    Test / scalacOptions ~= filterConsoleScalacOptions,
    commonSettings
  )

lazy val noPublish =
  Seq(publish := {}, publishLocal := {}, publishArtifact := false)

lazy val root = project
  .in(file("."))
  .enablePlugins(JmhPlugin)
  .settings(name := "sqs4s", commonSettings, noPublish)
  .aggregate(native)

lazy val docs = project
  .in(file("sqs4s-docs"))
  .settings(moduleName := "sqs4s-docs", commonSettings, noPublish)
  .dependsOn(native)
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
