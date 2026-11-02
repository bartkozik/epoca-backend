package pl.iterators.epoca.utils.log

import org.slf4j
import org.slf4j.{LoggerFactory, MDC}

trait LoggingSupport {
  private lazy val cls: Class[_ <: LoggingSupport] = getClass
  lazy val logger: slf4j.Logger                    = LoggerFactory.getLogger(cls)
  lazy val loggingAdapter                          = new SLF4JLoggingAdapter(logger)

  trait Logger[F[_]] {
    def error(message: => String): F[Unit]
    def warning(message: => String): F[Unit]
    def info(message: => String): F[Unit]
    def debug(message: => String): F[Unit]
    def error(t: Throwable)(message: => String): F[Unit]
    def warning(t: Throwable)(message: => String): F[Unit]
  }

  object Logger {
    private def withMDC[F[_]: TracingContextSource, T](t: => T): F[T] =
      implicitly[TracingContextSource[F]].map { ctx =>
        ctx.foreach(c => MDC.put(c.contextName, c.context))
        try t
        finally ctx.foreach(c => MDC.remove(c.contextName))
      }

    def apply[F[_]: TracingContextSource]: Logger[F] = new Logger[F] {
      override def error(message: => String): F[Unit] = withMDC(loggingAdapter.error(message))

      override def warning(message: => String): F[Unit] = withMDC(loggingAdapter.warning(message))

      override def info(message: => String): F[Unit] = withMDC(loggingAdapter.info(message))

      override def debug(message: => String): F[Unit] = withMDC(loggingAdapter.debug(message))

      override def error(t: Throwable)(message: => String): F[Unit] = withMDC(loggingAdapter.error(t, message))

      override def warning(t: Throwable)(message: => String): F[Unit] = withMDC(loggingAdapter.warning(t, message))

    }
  }
}
