package sqs4s.api.lo

import cats.effect.Async
import cats.syntax.all._
import fs2.Chunk
import org.http4s.Request
import org.typelevel.log4cats.Logger
import sqs4s.api.SqsConfig
import sqs4s.api.errors.UnexpectedResponseError
import sqs4s.serialization.SqsSerializer

import scala.concurrent.duration.Duration
import scala.xml.Elem

final case class SendMessageBatch[F[_]: Async, T](
  messages: Chunk[SendMessageBatch.Entry[T]]
)(implicit serializer: SqsSerializer[T])
    extends Action[F, SendMessageBatch.Result] {

  private val entries = messages.map { entry =>
    entry.attributes.zipWithIndex.toList.flatMap {
      case ((key, value), index) =>
        List(
          s"MessageAttribute.${index + 1}.Name" -> key,
          s"MessageAttribute.${index + 1}.Value" -> value
        )
    } ++ List(
      Some("Id" -> entry.id),
      Some("MessageBody" -> serializer.serialize(entry.message)),
      entry.delay.map(d => "DelaySeconds" -> d.toSeconds.toString),
      entry.groupId.map(gid => "MessageGroupId" -> gid),
      entry.dedupId.map(did => "MessageDeduplicationId" -> did)
    ).flatten
  }.zipWithIndex.toList.flatMap {
    case (flattenEntry, index) =>
      flattenEntry.map {
        case (key, value) =>
          s"SendMessageBatchRequestEntry.${index + 1}.$key" -> value
      }
  }

  private def successesEntry(elem: Elem): List[SendMessageBatch.Success] =
    (elem \\ "SendMessageBatchResultEntry").toList.map { entry =>
      val id = (entry \\ "Id").text
      val messageId = (entry \\ "MessageId").text
      val md5Body = (entry \\ "MD5OfMessageBody").text
      val md5OfMessageAttributes = entry \\ "MD5OfMessageAttributes"
      val seqNumber = entry \\ "SequenceNumber"
      SendMessageBatch.Success(
        id,
        md5Body,
        md5OfMessageAttributes.nonEmpty
          .guard[Option]
          .as(md5OfMessageAttributes.text),
        messageId,
        seqNumber.nonEmpty.guard[Option].map(_ => BigInt(seqNumber.text))
      )
    }

  private def errorsEntry(elem: Elem): List[SendMessageBatch.Error] =
    (elem \\ "BatchResultErrorEntry").toList.map { error =>
      val id = (error \\ "Id").text
      val message = error \\ "Message"
      val senderFault = (error \\ "SenderFault").text
      val code = (error \\ "Code").text
      SendMessageBatch.Error(
        id,
        code,
        message.nonEmpty.guard[Option].as(message.text),
        senderFault.toBoolean
      )
    }

  def mkRequest(config: SqsConfig[F], logger: Logger[F]): F[Request[F]] = {
    val params =
      List("Action" -> "SendMessageBatch") ++ version ++ entries

    SignedRequest.post[F](
      params,
      config.queue,
      config.credentials,
      config.region
    ).render(logger)
  }

  def parseResponse(response: Elem): F[SendMessageBatch.Result] = {
    if (
      (response \\ "BatchResultErrorEntry").isEmpty &&
      (response \\ "SendMessageBatchResultEntry").isEmpty
    ) {
      Async[F].raiseError(
        UnexpectedResponseError(
          "BatchResultErrorEntry, SendMessageBatchResultEntry",
          response
        )
      )
    } else {
      SendMessageBatch
        .Result(
          (response \\ "RequestId").text,
          successesEntry(response),
          errorsEntry(response)
        )
        .pure[F]
    }
  }
}

object SendMessageBatch {
  final case class Entry[T](
    id: String,
    message: T,
    attributes: Map[String, String] = Map.empty,
    delay: Option[Duration] = None,
    dedupId: Option[String] = None,
    groupId: Option[String] = None
  )

  final case class Result(
    requestId: String,
    successes: List[Success],
    errors: List[Error]
  )

  final case class Success(
    id: String,
    messageBodyMd5: String,
    messageAttributesMd5: Option[String],
    messageId: String,
    sequenceNumber: Option[BigInt]
  )

  final case class Error(
    id: String,
    code: String,
    message: Option[String],
    senderFault: Boolean
  )
}
