package pl.iterators.epoca.storage

import cats.effect.IO

trait FileStorage {

  def put(
    key: String,
    bytes: Array[Byte],
    contentType: String
  ): IO[Unit]

  def delete(key: String): IO[Unit]

  def publicUrl(key: String): String
}
