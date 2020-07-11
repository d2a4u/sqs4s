package sqs4s.internal.aws4.aws4suite

import java.io.File

import cats.effect.IO
import cats.implicits._
import org.scalatest.Inspectors
import sqs4s.IOSpec
import sqs4s.internal.aws4._
import sqs4s.internal.aws4.common._

import scala.io.Source

class StringToSignSpec extends IOSpec with Inspectors {

  val region = "us-east-1"
  val service = "service"

  def expect(testName: String): String =
    Source
      .fromResource(s"aws-sig-v4-test-suite/$testName/$testName.sts")
      .getLines()
      .mkString("\n")

  def canonicalRequest(fileName: String): String =
    Source
      .fromResource(s"aws-sig-v4-test-suite/$fileName/$fileName.creq")
      .getLines()
      .mkString("\n")

  "String to sign" should "be correct" in {
    val testSuite = getClass.getResource("/aws-sig-v4-test-suite")
    val filesF = IO.suspend {
      val folder = new File(testSuite.getPath)
      if (folder.exists && folder.isDirectory)
        folder.listFiles.toList
          .filter(
            f => !List("normalize-path", "post-sts-token").contains(f.getName)
          )
          .pure[IO]
      else new Exception("No test files found").raiseError[IO, List[File]]
    }

    val files = filesF.unsafeRunSync()

    forAll(files) { file =>
      val testCaseName = file.getName
      stringToSign[IO](
        region,
        service,
        canonicalRequest(testCaseName),
        testDateTime
      ).unsafeRunSync() shouldEqual expect(testCaseName)
    }
  }
}
