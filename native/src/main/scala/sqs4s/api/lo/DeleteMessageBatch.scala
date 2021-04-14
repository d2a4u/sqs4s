package sqs4s.api.lo

import cats.effect.Async
import cats.syntax.all._
import org.http4s.Request
import org.typelevel.log4cats.Logger
import sqs4s.api.SqsConfig
import sqs4s.api.errors.UnexpectedResponseError

import scala.xml.Elem

final case class DeleteMessageBatch[F[_]: Async](
  entries: Seq[DeleteMessageBatch.Entry]
) extends Action[F, DeleteMessageBatch.Result] {

  private val receiptHandles = entries.iterator.zipWithIndex.flatMap {
    case (entry, index) =>
      List(
        s"DeleteMessageBatchRequestEntry.${index + 1}.Id" -> entry.id,
        s"DeleteMessageBatchRequestEntry.${index + 1}.ReceiptHandle" -> entry.receiptHandle.value
      )
  }.toList

  def mkRequest(config: SqsConfig[F], logger: Logger[F]): F[Request[F]] = {
    val params = receiptHandles ++ List(
      Some("Action" -> "DeleteMessageBatch"),
      version
    ).flatten

    SignedRequest.post[F](
      params,
      config.queue,
      config.credentials,
      config.region
    ).render(logger)
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
      Async[F].raiseError(
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
  final case class Entry(id: String, receiptHandle: ReceiptHandle)

  final case class Result(
    requestId: String,
    successes: List[Success],
    errors: List[Error]
  )

  final case class Success(id: String)

  final case class Error(
    id: String,
    code: String,
    message: Option[String],
    senderFault: Boolean
  )
}
