import sbt.Keys.organization
import sbt.addCompilerPlugin

val circeVersion = "0.13.0"
val fs2Version = "2.4.2"
val http4sVersion = "0.21.18"
val logbackVersion = "1.2.3"

val circe = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

lazy val dependencies = Seq(
  "org.http4s" %% "http4s-client" % http4sVersion,
  "org.http4s" %% "http4s-scala-xml" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
  "io.chrisdavenport" %% "log4cats-slf4j" % "1.1.1",
  "co.fs2" %% "fs2-core" % fs2Version,
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359",
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.2"
)

lazy val testDependencies = Seq(
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.scalatest" %% "scalatest" % "3.2.0",
  "ch.qos.logback" % "logback-classic" % logbackVersion,
  "org.scalacheck" %% "scalacheck" % "1.14.3"
) ++ circe

lazy val commonSettings = Seq(
  organization in ThisBuild := "io.github.d2a4u",
  scalaVersion := "2.13.5",
  crossScalaVersions := Seq("2.12.13", "2.13.5"),
  parallelExecution in Test := false,
  scalafmtOnCompile := true,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  homepage := Some(url("https://d2a4u.github.io/sqs4s/")),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/d2a4u/sqs4s"),
      "git@github.com:d2a4u/sqs4s.git"
    )
  ),
  developers := List(
    Developer(
      "d2a4u",
      "D A Khu",
      "d2a4u@users.noreply.github.com",
      url("https://github.com/d2a4u")
    )
  ),
  pgpPublicRing := file("/tmp/local.pubring.asc"),
  pgpSecretRing := file("/tmp/local.secring.asc"),
  releaseEarlyWith in Global := SonatypePublisher,
  sonatypeProfileName := "io.github.d2a4u",
  releaseEarlyEnableSyncToMaven := true,
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
    testOptions in ItTest += Tests.Argument("-oD")
  )
  .settings(
    name := "sqs4s-native",
    libraryDependencies ++= dependencies ++ testDependencies.map(_ % "it,test"),
    scalacOptions in Test ~= filterConsoleScalacOptions,
    scalacOptions in Compile ~= filterConsoleScalacOptions,
    commonSettings
  )

lazy val noPublish =
  Seq(publish := {}, publishLocal := {}, publishArtifact := false)

lazy val root = project
  .in(file("."))
  .settings(name := "sqs4s", commonSettings, noPublish)
  .aggregate(native)

lazy val docs = project
  .in(file("sqs4s-docs"))
  .settings(moduleName := "sqs4s-docs", commonSettings, noPublish)
  .dependsOn(native)
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
