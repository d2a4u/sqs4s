import sbt.Keys.organization
import sbt.addCompilerPlugin

val circeVersion = "0.10.0"
val fs2Version = "1.0.4"

val circe = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion % "test")

lazy val coreDependencies = Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
) ++ circe

lazy val sqsDependencies = coreDependencies ++ Seq(
  "com.amazonaws" % "amazon-sqs-java-messaging-lib" % "1.0.6",
  "org.elasticmq" %% "elasticmq-rest-sqs" % "0.14.6" % "test"
)

lazy val settings = Seq(
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
  bintrayRepository := "sqs4s",
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.0")
)

lazy val global = project
  .in(file("."))
  .settings(
    organization in ThisBuild := "io.sqs4s",
    scalaVersion := "2.12.8",
    settings
  )
  .aggregate(
    core,
    sqs
  )

lazy val core = project
  .settings(
    name := "sqs4s-core",
    libraryDependencies ++= coreDependencies,
    settings
  )

lazy val sqs = project
  .settings(
    name := "sqs4s-sqs",
    libraryDependencies ++= coreDependencies ++ sqsDependencies,
    settings
  )
  .dependsOn(
    core
  )
