package pl.iterators.epoca.utils.json

import com.github.pjfanning.pekkohttpcirce.FailFastCirceSupport
import io.circe.generic.AutoDerivation
import io.circe.{Decoder, Encoder}
import pl.iterators.kebs.circe.enums.{KebsCirceEnums, KebsCirceValueEnums}
import pl.iterators.kebs.core.instances.InstanceConverter
import pl.iterators.kebs.enumeratum.KebsEnumeratum
import pl.iterators.kebs.instances.{NetInstances, TimeInstances, UtilInstances}
import pl.iterators.kebs.tagged._
import pl.iterators.kebs.tagged.circe.KebsTaggedCirce

import scala.util.Try

private[json] trait InstanceConverterCirce {
  implicit def instanceConverterEncoder[T, A](implicit converter: InstanceConverter[T, A], encoder: Encoder[A]): Encoder[T] =
    encoder.contramap(converter.encode)

  implicit def instanceConverterDecoder[T, A](implicit converter: InstanceConverter[T, A], decoder: Decoder[A]): Decoder[T] =
    decoder.emap(value => Try(converter.decode(value)).toEither.left.map(_.getMessage))
}

private[epoca] sealed trait GenericTaggedCirce[A]

private[epoca] object GenericTaggedCirce {
  implicit def generic[A]: GenericTaggedCirce[A] = new GenericTaggedCirce[A] {}

  implicit def stringAmbiguous1: GenericTaggedCirce[String] = new GenericTaggedCirce[String] {}
  implicit def stringAmbiguous2: GenericTaggedCirce[String] = new GenericTaggedCirce[String] {}

  implicit def uuidAmbiguous1: GenericTaggedCirce[java.util.UUID] = new GenericTaggedCirce[java.util.UUID] {}
  implicit def uuidAmbiguous2: GenericTaggedCirce[java.util.UUID] = new GenericTaggedCirce[java.util.UUID] {}

  implicit def bigDecimalAmbiguous1: GenericTaggedCirce[BigDecimal] = new GenericTaggedCirce[BigDecimal] {}
  implicit def bigDecimalAmbiguous2: GenericTaggedCirce[BigDecimal] = new GenericTaggedCirce[BigDecimal] {}

  implicit def intAmbiguous1: GenericTaggedCirce[Int] = new GenericTaggedCirce[Int] {}
  implicit def intAmbiguous2: GenericTaggedCirce[Int] = new GenericTaggedCirce[Int] {}

  implicit def jsonAmbiguous1: GenericTaggedCirce[io.circe.Json] = new GenericTaggedCirce[io.circe.Json] {}
  implicit def jsonAmbiguous2: GenericTaggedCirce[io.circe.Json] = new GenericTaggedCirce[io.circe.Json] {}
}

private[json] trait LowPriorityTaggedCirce {
  implicit def taggedEncoder[A, U](implicit encoder: Encoder[A], _generic: GenericTaggedCirce[A]): Encoder[A @@ U] =
    encoder.contramap(value => value: A)

  implicit def taggedDecoder[A, U](implicit decoder: Decoder[A], _generic: GenericTaggedCirce[A]): Decoder[A @@ U] =
    decoder.map(_.taggedWith[U])
}

private[json] trait TaggedCirce extends LowPriorityTaggedCirce with KebsTaggedCirce

trait JsonProtocol
    extends FailFastCirceSupport
    with AutoDerivation
    with InstanceConverterCirce
    with TaggedCirce
    with KebsEnumeratum
    with KebsCirceEnums
    with KebsCirceValueEnums
    with TimeInstances
    with UtilInstances
    with NetInstances

object JsonProtocol extends JsonProtocol
