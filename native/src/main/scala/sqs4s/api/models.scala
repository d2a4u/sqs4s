package sqs4s.api

import cats.implicits._
import org.http4s.Status

import scala.xml.Elem

object responses {
  case class MessageSent(
    messageBodyMd5: String,
    messageAttributesMd5: String,
    messageId: String,
    requestId: String)
}

object errors {
  sealed trait SqsError extends Exception

  case class BasicAwsSqsError(status: Status, raw: Elem) extends SqsError {
    override def getMessage: String =
      s"""Http code: ${status.code}
         |Raw: ${raw.toString}
     """.stripMargin
  }

  case class AwsSqsError(
    status: Status,
    `type`: String,
    code: String,
    message: String,
    requestId: String)
      extends SqsError {
    override def getMessage: String =
      s"""Http code: ${status.code}
         |Type: ${`type`}
         |Code: $code
         |Request ID: $requestId
         |Message: $message""".stripMargin
  }

  object SqsError {
    def fromXml(status: Status, xml: Elem): SqsError = {
      (
        (xml \\ "Type").headOption.map(_.text),
        (xml \\ "Code").headOption.map(_.text),
        (xml \\ "Message").headOption.map(_.text),
        (xml \\ "RequestId").headOption.map(_.text)
      ).mapN { (typ, code, msg, id) =>
          AwsSqsError(status, typ, code, msg, id)
        }
        .getOrElse(BasicAwsSqsError(status, xml))
    }
  }
}
