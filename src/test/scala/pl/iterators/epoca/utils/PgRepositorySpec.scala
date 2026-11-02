package pl.iterators.epoca.utils

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.utils.db.BaseTable
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.util.Properties
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

trait PgRepositorySpec extends SpecificationLike with Generators {

  sequential

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  def withDatabase[T](a: => DBIO[T]): T =
    Await.result(
      db.run {
        a.map[T](r => throw AbortTx(r)).transactionally.asTry.map[T] {
          case Failure(AbortTx(value)) => value.asInstanceOf[T]
          case Failure(t)              => throw t
          case Success(_)              => sys.error("This should never happen")
        }
      },
      Duration.Inf
    )

  private case class AbortTx[T](value: T) extends Exception

  private val url      = sys.env.getOrElse("TEST_DB_URL", "epoca")
  private val user     = sys.env.getOrElse("TEST_DB_USER", "postgres")
  private val password = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")

  protected lazy val db: BaseTable.driver.backend.Database = Database.forURL(
    url = url,
    user = user,
    password = password,
    driver = "org.postgresql.Driver",
    executor = AsyncExecutor(getClass.getCanonicalName, minThreads = 1, maxThreads = 1, queueSize = 20, maxConnections = 1),
    prop = new Properties() {}
  )

}
