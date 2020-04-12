import sbt.Keys.organization
import sbt.addCompilerPlugin

val circeVersion = "0.11.2"
val fs2Version = "1.0.5"
val http4sVersion = "0.20.6"

val circe = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

lazy val dependencies = Seq(
  "org.http4s"             %% "http4s-blaze-client"      % http4sVersion,
  "org.http4s"             %% "http4s-scala-xml"         % http4sVersion,
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
  "io.chrisdavenport"      %% "log4cats-slf4j"           % "1.0.1",
  "co.fs2"                 %% "fs2-core"                 % fs2Version,
  "javax.xml.bind"         % "jaxb-api"                  % "2.4.0-b180830.0359"
)

lazy val testDependencies = Seq(
  "org.scalatest"       %% "scalatest"             % "3.0.8",
  "com.danielasfregola" %% "random-data-generator" % "2.7",
  "ch.qos.logback"      % "logback-classic"        % "1.2.3"
) ++ circe

lazy val commonSettings = Seq(
  organization in ThisBuild := "io.sqs4s",
  scalaVersion := "2.12.11",
  crossScalaVersions := Seq("2.12.11"),
  parallelExecution in Test := false,
  scalafmtOnCompile := true,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  bintrayRepository := "sqs4s",
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  releaseCrossBuild := true,
  bintrayReleaseOnPublish := false,
  addCompilerPlugin(
    ("org.typelevel" %% "kind-projector" % "0.10.3").cross(CrossVersion.binary)
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

lazy val root = project
  .in(file("."))
  .enablePlugins(JmhPlugin)
  .settings(name := "sqs4s", noPublish, commonSettings)
  .aggregate(native)

lazy val noPublish =
  Seq(publish := {}, publishLocal := {}, publishArtifact := false)
