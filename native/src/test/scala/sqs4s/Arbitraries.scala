package sqs4s

import java.time.Instant

import org.scalacheck.Gen
import sqs4s.auth.Credential

trait Arbitraries {
  implicit val genCredential: Gen[Credential] =
    for {
      accessKeyId <- Gen.alphaNumStr
      secretAccessKey <- Gen.alphaNumStr
      token <- Gen.alphaNumStr
      lastUpdated <- Gen.chooseNum(1L, 1000L).map(num =>
        Instant.now().plusSeconds(num))
      expiration <- Gen.chooseNum(1L, 1000L).map(num =>
        lastUpdated.plusSeconds(num))
    } yield {
      Credential(
        accessKeyId,
        secretAccessKey,
        token,
        lastUpdated,
        expiration
      )
    }

  implicit val genCredentials: Gen[List[Credential]] = Gen.listOf(genCredential)
}
