package pl.iterators.epoca.utils.job

import cats.effect.IO
import io.circe.{Decoder, DecodingFailure, Encoder, Error}
import pl.iterators.epoca.utils.job.TriggeredJob.Codec

trait TriggeredJob[T] {
  val codec: Codec[T]

  final def doExecute(payload: TriggeredJob.Payload): IO[Unit] =
    execute(codec.from(payload))

  def execute(t: T): IO[Unit]

  final lazy val jobName = getClass.getName

}

object TriggeredJob {
  type Payload = Map[String, Any]

  trait Codec[T] {
    def to(t: T): Payload

    def from(map: Payload): T
  }

  implicit val identityCodec: Codec[Payload] = new Codec[Payload] {
    override def to(t: Payload): Payload = t

    override def from(map: Payload): Payload = map
  }

  implicit def jsonBasedCodec[T](implicit e: Encoder[T], d: Decoder[T]): Codec[T] = new Codec[T] {
    import io.circe.parser._

    private lazy val key = "json"

    override def to(t: T): Payload =
      Map(key -> e(t).noSpaces)

    override def from(payload: Payload): T =
      (for {
        string  <- payload.get(key).toRight[Error](DecodingFailure("Unable to get proper key from job map", Nil))
        parsed  <- parse(string.asInstanceOf[String])
        decoded <- d.decodeJson(parsed)
      } yield decoded).toOption.getOrElse(sys.error("Unable to get proper key from job map"))
  }
}
