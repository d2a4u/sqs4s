package sqs4s.auth

import io.circe.parser._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class modelSpec extends AnyFlatSpecLike with Matchers {

  behavior.of("CredentialResponse")

  it should "decode raw Json credential response" in {
    val raw = """{
      "RoleArn" : "arn:aws:iam::12345:role/foo-12345",
      "AccessKeyId" : "key",
      "SecretAccessKey" : "secret",
      "Token" : "token",
      "Expiration" : "2020-07-19T02:25:10Z"
    }"""

    decode[CredentialResponse](raw) shouldBe a[Right[_, _]]
  }
}
