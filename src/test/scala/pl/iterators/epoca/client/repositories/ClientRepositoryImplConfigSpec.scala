package pl.iterators.epoca.client.repositories

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike

import java.net.URLClassLoader
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Using

class ClientRepositoryImplConfigSpec extends SpecificationLike {
  "ClientRepositoryImpl.Config" should {
    "default to a five-second PostgreSQL timeout" in {
      ClientRepositoryImpl.Config.Default.lockTimeout shouldEqual 5.seconds
      ClientRepositoryImpl.Config.Default.postgresValue shouldEqual "5000ms"
    }

    "load a custom timeout from Typesafe Config" in {
      val config = ConfigFactory.parseString("client.mutation.lockTimeout = 125ms")

      ClientRepositoryImpl.Config(config).lockTimeout shouldEqual 125.millis
      ClientRepositoryImpl.Config(config).postgresValue shouldEqual "125ms"
    }

    "reject timeouts shorter than one millisecond" in {
      ClientRepositoryImpl.Config(-1.millis) must throwA[IllegalArgumentException]
      ClientRepositoryImpl.Config(Duration.Zero) must throwA[IllegalArgumentException]
      ClientRepositoryImpl.Config(999.micros) must throwA[IllegalArgumentException]
    }

    "expose an optional environment override in application.conf" in {
      val productionResources = ClientRepositoryImpl.getClass.getProtectionDomain.getCodeSource.getLocation
      val resourceText = Using.resource(new URLClassLoader(Array(productionResources), null)) { classLoader =>
        Using.resource(Source.fromResource("application.conf", classLoader))(_.mkString)
      }
      val overrideLine = "lockTimeout = $" + "{?CLIENT_MUTATION_LOCK_TIMEOUT}"

      resourceText must contain(overrideLine)
    }
  }
}
