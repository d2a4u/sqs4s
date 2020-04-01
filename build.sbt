import sbt.Keys.organization
import sbt.addCompilerPlugin

val circeVersion = "0.11.2"
val fs2Version = "1.0.5"
val http4sVersion = "0.20.6"

val circe = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion % Test)

lazy val coreDependencies = Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359",
  "org.scalatest" %% "scalatest" % "3.0.8" % Test,
  "com.danielasfregola" %% "random-data-generator" % "2.7" % Test
) ++ circe

lazy val sqsDependencies = Seq(
  "com.amazonaws" % "amazon-sqs-java-messaging-lib" % "1.0.8",
  "org.elasticmq" %% "elasticmq-rest-sqs" % "0.14.15" % Test
) ++ circe

lazy val nativeDependencies = Seq(
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-scala-xml" % http4sVersion,
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
  "io.chrisdavenport" %% "log4cats-slf4j" % "1.0.1",
  "ch.qos.logback" % "logback-classic" % "1.2.3" % Test,
) ++ circe

lazy val benchmarkDependencies = Seq(
  "org.slf4j" % "log4j-over-slf4j" % "1.7.28" % Test,
  "log4j" % "log4j" % "1.2.17" % Test
)

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
  pomIncludeRepository := { _ =>
    false
  },
  releaseCrossBuild := true,
  bintrayReleaseOnPublish := false,
  addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.10.3").cross(CrossVersion.binary))
)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "sqs4s-core",
    libraryDependencies ++= coreDependencies,
    commonSettings
  )

lazy val sqs = project
  .in(file("sqs"))
  .settings(
    name := "sqs4s-sqs",
    libraryDependencies ++= coreDependencies ++ sqsDependencies,
    commonSettings
  )
  .dependsOn(
    core
  )

lazy val native = project
  .in(file("native"))
  .settings(
    name := "sqs4s-native",
    libraryDependencies ++= coreDependencies ++ nativeDependencies,
    scalacOptions in Test ~= filterConsoleScalacOptions,
    scalacOptions in Compile ~= filterConsoleScalacOptions,
    commonSettings
  )

lazy val benchmark = project
  .in(file("benchmark"))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "sqs4s-benchmark",
    libraryDependencies ++= coreDependencies ++ sqsDependencies ++ benchmarkDependencies,
    noPublish,
    commonSettings,
    sourceDirectory in Jmh := (sourceDirectory in Test).value,
    classDirectory in Jmh := (classDirectory in Test).value,
    dependencyClasspath in Jmh := (dependencyClasspath in Test).value,
    compile in Jmh := (compile in Jmh).dependsOn(compile in Test).value,
    run in Jmh := (run in Jmh).dependsOn(Keys.compile in Jmh).evaluated,
    scalacOptions in Test ~= filterConsoleScalacOptions,
    scalacOptions in Compile ~= filterConsoleScalacOptions
  )
  .dependsOn(
    core,
    sqs % "test->test"
  )

lazy val root = project
  .in(file("."))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "sqs4s",
    noPublish,
    commonSettings
  )
  .aggregate(
    core,
    sqs,
    benchmark,
    native
  )

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)
