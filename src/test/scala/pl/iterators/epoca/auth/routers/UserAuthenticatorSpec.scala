package pl.iterators.epoca.auth.routers

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.slf4j.LoggerFactory
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.auth.services.AuthenticateService
import pl.iterators.epoca.auth.services.AuthenticateService.AuthenticateTokenResult

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

class UserAuthenticatorSpec extends SpecificationLike {
  implicit val runtime: IORuntime = IORuntime.global

  "human bearer authentication" should {
    "return a generic challenge without logging the supplied credential on service failure" in {
      val sentinel = "ct520-bearer-secret-sentinel"
      val service = new AuthenticateService {
        override def authenticateToken(jwt: String): IO[AuthenticateTokenResult] =
          IO.raiseError(new RuntimeException("authentication state unavailable"))
      }
      val sut      = new AuthServiceUserAuthenticator(service)
      val logger   = LoggerFactory.getLogger(classOf[AuthServiceUserAuthenticator]).asInstanceOf[ch.qos.logback.classic.Logger]
      val oldLevel = logger.getLevel
      val appender = new ListAppender[ILoggingEvent]
      appender.start()
      logger.setLevel(Level.WARN)
      logger.addAppender(appender)

      try {
        val result = Await.result(sut(Some(OAuth2BearerToken(sentinel))), 5.seconds)
        val logs   = appender.list.asScala.map(_.getFormattedMessage).mkString("\n")

        Seq(result.isLeft, !logs.contains(sentinel), logs.contains("OAuth-compatible bearer authentication failed")) shouldEqual Seq(true, true, true)
      } finally {
        logger.detachAppender(appender)
        logger.setLevel(oldLevel)
        appender.stop()
      }
    }
  }
}
