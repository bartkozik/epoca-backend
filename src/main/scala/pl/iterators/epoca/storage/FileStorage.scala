package pl.iterators.epoca.storage

import cats.effect.IO

/** Generic object storage. Keys are slash separated relative paths such as `avatars/{userId}/{hash}.png`. */
trait FileStorage {

  /** Stores (or overwrites) the object under the given key. */
  def put(
    key: String,
    bytes: Array[Byte],
    contentType: String
  ): IO[Unit]

  /** Deletes the object under the given key; deleting a missing key is not an error (idempotent, like S3 DeleteObject). */
  def delete(key: String): IO[Unit]

  /** Publicly reachable URL of the object stored under the given key. Does not check that the object exists. */
  def publicUrl(key: String): String
}
