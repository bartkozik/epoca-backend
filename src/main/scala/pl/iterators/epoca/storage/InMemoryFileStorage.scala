package pl.iterators.epoca.storage

import cats.effect.IO

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** Non-persistent storage for local development and tests. Objects are lost on restart and are not served over HTTP. */
class InMemoryFileStorage(publicBaseUrl: String) extends FileStorage {
  private val objects = new ConcurrentHashMap[String, (Array[Byte], String)]()

  override def put(
    key: String,
    bytes: Array[Byte],
    contentType: String
  ): IO[Unit] =
    IO.delay(objects.put(key, (bytes, contentType))).void

  override def delete(key: String): IO[Unit] =
    IO.delay(objects.remove(key)).void

  override def publicUrl(key: String): String = s"${publicBaseUrl.stripSuffix("/")}/$key"

  def get(key: String): Option[(Array[Byte], String)] = Option(objects.get(key))
  def keys: Set[String]                               = objects.keySet().asScala.toSet
}
