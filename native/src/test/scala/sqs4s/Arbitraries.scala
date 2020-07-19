package sqs4s

import java.time.Instant

import org.scalacheck.Gen
import sqs4s.auth.CredentialResponse

trait Arbitraries {
  implicit val genCredential: Gen[CredentialResponse] =
    for {
      accessKeyId <- Gen.alphaNumStr
      secretAccessKey <- Gen.alphaNumStr
      token <- Gen.alphaNumStr
      optLastUpdated <- Gen.option(Gen.chooseNum(1L, 1000L).map(num =>
        Instant.now().minusSeconds(num)))
      expiration <- Gen.chooseNum(1L, 1000L).map(num =>
        Instant.now().plusSeconds(num))
    } yield {
      CredentialResponse(
        accessKeyId,
        secretAccessKey,
        token,
        optLastUpdated,
        expiration
      )
    }

  implicit val genCredentials: Gen[List[CredentialResponse]] =
    Gen.listOfN(2, genCredential)

  val genExpiredCreds: Gen[List[CredentialResponse]] = genCredentials.map {
    creds =>
      creds.map { cred =>
        cred.copy(expiration = Instant.now.minusSeconds(10))
      }
  }
}
