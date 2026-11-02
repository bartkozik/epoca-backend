package pl.iterators.epoca.utils.random

import cats.effect.Sync
import cats.effect.std.UUIDGen

import java.util.UUID

case object IdGenerator {
  def nextId[F[_]: Sync, T <: UUID]: F[T] = UUIDGen.randomUUID[F].asInstanceOf[F[T]]
}
