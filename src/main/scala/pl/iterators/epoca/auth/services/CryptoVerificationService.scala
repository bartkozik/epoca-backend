package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthDomain.{Base64PublicKey, CryptoSignature, CryptoVerificationData, SignatureAlgorithm}
import pl.iterators.epoca.auth.services.CryptoVerificationService.CryptoVerificationResult
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIO
import pl.iterators.sealedmonad.syntax._

import java.security.spec.X509EncodedKeySpec
import java.security.{KeyFactory, PublicKey, Signature}
import java.util.Base64

class CryptoVerificationService()(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIO[CryptoVerificationResult](ioRuntime)
    with LoggingSupport {

  def verify(
    algorithm: SignatureAlgorithm,
    base64PublicKey: Base64PublicKey,
    cryptoSignature: CryptoSignature,
    cryptoVerificationData: CryptoVerificationData
  ): IO[CryptoVerificationResult] =
    (for {
      signature <- getSignature(algorithm)
      publicKey <- getPublicKey(base64PublicKey, algorithm)
      _         <- signatureVerify(signature, publicKey, cryptoSignature, cryptoVerificationData)
    } yield CryptoVerificationResult.Verified).run

  private def getSignature(algorithm: SignatureAlgorithm): Step[Signature] =
    IO.delay(Signature.getInstance(algorithm.entryName))
      .attempt
      .flatMap {
        case Right(signature) => IO.pure(Right(signature))
        case Left(throwable) =>
          Logger[IO].error(s"Unable to get signature $throwable").as(Left(CryptoVerificationResult.InvalidAlgorithm))
      }
      .fromEither

  private def getPublicKey(key: Base64PublicKey, algorithm: SignatureAlgorithm): Step[PublicKey] =
    IO.delay {
      val kf             = KeyFactory.getInstance(algorithm.keyAlgorithm.entryName)
      val byteKey        = Base64.getDecoder.decode(key.getBytes)
      val encodedKeySpec = new X509EncodedKeySpec(byteKey)
      kf.generatePublic(encodedKeySpec)
    }.attempt
      .flatMap {
        case Right(publicKey) => IO.pure(Right(publicKey))
        case Left(throwable) =>
          Logger[IO].error(throwable)(s"Unable to get public key $throwable").as(Left(CryptoVerificationResult.UnableToCreatePublicKey))
      }
      .fromEither

  private def signatureVerify(
    signature: Signature,
    publicKey: PublicKey,
    cryptoSignature: CryptoSignature,
    cryptoVerificationData: CryptoVerificationData
  ): Step[Unit] =
    IO.delay {
      signature.initVerify(publicKey)
      val signatureBytes = Base64.getDecoder.decode(cryptoSignature.getBytes)
      signature.update(cryptoVerificationData.getBytes)
      signature.verify(signatureBytes)
    }.attempt
      .flatMap {
        case Right(verified) =>
          IO.pure {
            if (verified) Right(())
            else Left(CryptoVerificationResult.NotVerified)
          }
        case Left(throwable) =>
          Logger[IO].error(s"Unable to get verify k$throwable").as(Left(CryptoVerificationResult.NotVerified))
      }
      .fromEither

}

object CryptoVerificationService {
  sealed trait CryptoVerificationResult
  object CryptoVerificationResult {
    final case object InvalidAlgorithm        extends CryptoVerificationResult
    final case object UnableToCreatePublicKey extends CryptoVerificationResult
    final case object NotVerified             extends CryptoVerificationResult
    final case object Verified                extends CryptoVerificationResult
  }

}
