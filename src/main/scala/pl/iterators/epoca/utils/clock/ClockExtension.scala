package pl.iterators.epoca.utils.clock

import cats.Monad
import cats.effect.Clock
import cats.implicits.toFunctorOps

import java.time.Instant

trait ClockExtension {
  implicit class ClockExtension[F[_]: Monad](clock: Clock[F]) {
    def instantNow: F[Instant] = clock.realTime.map(d => Instant.ofEpochMilli(d.toMillis))
  }
}
