name := "sqs4s"

version := "0.1.0"

scalaVersion := "2.12.8"

val circeVersion = "0.10.0"
val fs2Version = "1.0.4"

val circe = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion % "test")

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "com.amazonaws" % "amazon-sqs-java-messaging-lib" % "1.0.6",
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "org.elasticmq" %% "elasticmq-rest-sqs" % "0.14.6" % "test"
) ++ circe

scalafmtOnCompile := true
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.0")

scalacOptions ++= Seq(
  // See other posts in the series for other helpful options
  "-feature",
  "-language:higherKinds"
)
parallelExecution in Test := false