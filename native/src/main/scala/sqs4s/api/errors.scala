package sqs4s.api

import cats.Show
import cats.data.NonEmptyList
import cats.implicits._
import sqs4s.api.lo.DeleteMessageBatch

import scala.xml.Elem

object errors {
  sealed trait SqsError extends Exception

  case class RetriableServerError(raw: String) extends SqsError

  case class BasicAwsSqsError(raw: Elem) extends SqsError {
    override def getMessage: String =
      s"Raw: ${raw.toString}"
  }

  case class UnexpectedResponseError(elemName: String, raw: Elem)
      extends SqsError {
    override def getMessage: String =
      s"""Expect XML element: $elemName
         |Raw: ${raw.toString}
     """.stripMargin
  }

  case object MessageTooLarge extends SqsError {
    override def getMessage: String =
      "Maximum SQS message size is 256KB or maximum batch of 10 entries"
  }

  case class AwsSqsError(
    `type`: String,
    code: String,
    message: String,
    requestId: String
  ) extends SqsError {
    override def getMessage: String =
      s"""Type: ${`type`}
         |Code: $code
         |Request ID: $requestId
         |Message: $message""".stripMargin
  }

  case class DeleteMessageBatchErrors(
    errors: NonEmptyList[DeleteMessageBatch.Error]
  ) extends SqsError

  object SqsError {
    implicit val show: Show[SqsError] = Show.show[SqsError](_.getMessage)

    def fromXml(xml: Elem): SqsError = {
      (
        (xml \\ "Type").headOption.map(_.text),
        (xml \\ "Code").headOption.map(_.text),
        (xml \\ "Message").headOption.map(_.text),
        (xml \\ "RequestId").headOption.map(_.text)
      ).mapN((typ, code, msg, id) => AwsSqsError(typ, code, msg, id))
        .getOrElse(BasicAwsSqsError(xml))
    }
  }
}
