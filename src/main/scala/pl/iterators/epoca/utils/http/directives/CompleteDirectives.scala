package pl.iterators.epoca.utils.http.directives

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.server.Directives.{complete => akkaComplete}
import org.apache.pekko.http.scaladsl.server.StandardRoute
import pl.iterators.epoca.utils.log.TracingContext

trait CompleteDirectives {
  def complete[T](io: => IO[T])(map: T => ToResponseMarshallable)(implicit runtime: IORuntime, tracingContext: TracingContext): StandardRoute =
    akkaComplete((TracingContext.set(tracingContext) >> io.map(map)).unsafeToFuture())
}
object CompleteDirectives extends CompleteDirectives
