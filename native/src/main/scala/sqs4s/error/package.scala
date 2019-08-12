package sqs4s

package object error {
  case class Unauthorized(message: String, requestId: String)
      extends Exception {
    override def getMessage: String =
      s"""RequestID: $requestId
         |Reason: $message""".stripMargin
  }
}
