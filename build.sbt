import sbt.Keys.organization
import sbt.addCompilerPlugin

val circeVersion = "0.11.1"
val fs2Version = "1.0.5"

val circe = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion % "test")

lazy val coreDependencies = Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test"
) ++ circe

lazy val sqsDependencies = Seq(
  "com.amazonaws" % "amazon-sqs-java-messaging-lib" % "1.0.6",
  "org.elasticmq" %% "elasticmq-rest-sqs" % "0.14.7" % "test"
)

lazy val rabbitmqDependencies = Seq(
  "com.rabbitmq" % "amqp-client" % "5.7.1"
)

lazy val commonSettings = Seq(
  organization in ThisBuild := "io.queue4s",
  scalaVersion := "2.12.8",
  crossScalaVersions := Seq("2.11.12", "2.12.8"),
  scalacOptions ++=  Seq(
    "-unchecked",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-deprecation",
    "-encoding",
    "utf8"
  ),
  parallelExecution in Test := false,
  scalafmtOnCompile := true,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  publishMavenStyle := true,
  bintrayRepository := "queue4s",
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  releaseCrossBuild := true,
  bintrayReleaseOnPublish := false,
  addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.10.3").cross(CrossVersion.binary))
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "queue4s",
    noPublish,
    commonSettings
  )
  .aggregate(
    core,
    sqs,
    rabbitmq
  )

lazy val core = project
  .in(file("core"))
  .settings(
    name := "queue4s-core",
    libraryDependencies ++= coreDependencies,
    commonSettings
  )

lazy val sqs = project
  .in(file("sqs"))
  .settings(
    name := "queue4s-sqs",
    libraryDependencies ++= coreDependencies ++ sqsDependencies,
    commonSettings
  )
  .dependsOn(
    core
  )

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val rabbitmq = project
  .in(file("rabbitmq"))
  .settings(
    name := "queue4s-rabbitmq",
    libraryDependencies ++= coreDependencies ++ rabbitmqDependencies,
    commonSettings
  )
