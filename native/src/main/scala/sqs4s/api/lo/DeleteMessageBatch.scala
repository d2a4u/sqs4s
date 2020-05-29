package sqs4s.api.lo

import cats.effect.{Clock, Sync}
import cats.implicits._
import fs2.Chunk
import org.http4s.Request
import sqs4s.api.SqsSettings
import sqs4s.api.errors.UnexpectedResponseError

import scala.xml.Elem

case class DeleteMessageBatch[F[_]: Sync: Clock](
  entries: Chunk[DeleteMessageBatch.Entry]
) extends Action[F, DeleteMessageBatch.Result] {

  private val receiptHandles = entries
    .map { entry =>
      List("Id" -> entry.id, "ReceiptHandle" -> entry.receiptHandle)
    }
    .zipWithIndex
    .toList
    .flatMap {
      case (flattenEntry, index) =>
        flattenEntry.map {
          case (key, value) =>
            s"DeleteMessageBatchRequestEntry.${index + 1}.$key" -> value
        }
    }

  def mkRequest(settings: SqsSettings): F[Request[F]] = {
    val params =
      List(
        "Action" -> "DeleteMessageBatch",
        "Version" -> "2012-11-05"
      ) ++ receiptHandles

    SignedRequest.post(params, settings.queue, settings.auth).render
  }

  private def successesEntry(elem: Elem): List[DeleteMessageBatch.Success] =
    (elem \\ "DeleteMessageBatchResultEntry").toList.map { entry =>
      DeleteMessageBatch.Success((entry \\ "Id").text)
    }

  private def errorsEntry(elem: Elem): List[DeleteMessageBatch.Error] =
    (elem \\ "BatchResultErrorEntry").toList.map { error =>
      val id = (error \\ "Id").text
      val message = error \\ "Message"
      val senderFault = (error \\ "SenderFault").text
      val code = (error \\ "Code").text
      DeleteMessageBatch.Error(
        id,
        code,
        message.nonEmpty.guard[Option].as(message.text),
        senderFault.toBoolean
      )
    }

  def parseResponse(response: Elem): F[DeleteMessageBatch.Result] = {
    if (
      (response \\ "BatchResultErrorEntry").isEmpty &&
      (response \\ "DeleteMessageBatchResultEntry").isEmpty
    ) {
      Sync[F].raiseError(
        UnexpectedResponseError(
          "BatchResultErrorEntry, DeleteMessageBatchResultEntry",
          response
        )
      )
    } else {
      DeleteMessageBatch
        .Result(
          (response \\ "RequestId").text,
          successesEntry(response),
          errorsEntry(response)
        )
        .pure[F]
    }
  }
}

object DeleteMessageBatch {
  case class Entry(id: String, receiptHandle: String)

  case class Result(
    requestId: String,
    successes: List[Success],
    errors: List[Error]
  )

  case class Success(id: String)

  case class Error(
    id: String,
    code: String,
    message: Option[String],
    senderFault: Boolean
  )
}
