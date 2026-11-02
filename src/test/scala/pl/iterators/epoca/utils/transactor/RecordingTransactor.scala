package pl.iterators.epoca.utils.transactor

import cats.effect.IO
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.dbio.DBIO

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

sealed trait TransactorCall
object TransactorCall {
  case object Execute                                              extends TransactorCall
  case object ExecutePinned                                        extends TransactorCall
  case object ExecuteTransactionally                               extends TransactorCall
  final case class ExecuteTransactionallyAt(level: IsolationLevel) extends TransactorCall
}

/** Mix into a transactor to record which execution methods (and isolation levels) were used. */
trait RecordingTransactor extends PostgresIOTransactor {
  private val recorded = new ConcurrentLinkedQueue[TransactorCall]()

  def calls: Seq[TransactorCall] = recorded.asScala.toList

  override def execute[T](f: => DBIO[T]): IO[T] = {
    recorded.add(TransactorCall.Execute)
    super.execute(f)
  }
  override def executePinned[T](f: => DBIO[T]): IO[T] = {
    recorded.add(TransactorCall.ExecutePinned)
    super.executePinned(f)
  }
  override def executeTransactionally[T](f: => DBIO[T]): IO[T] = {
    recorded.add(TransactorCall.ExecuteTransactionally)
    super.executeTransactionally(f)
  }
  override def executeTransactionally[T](level: IsolationLevel)(f: => DBIO[T]): IO[T] = {
    recorded.add(TransactorCall.ExecuteTransactionallyAt(level))
    super.executeTransactionally(level)(f)
  }
}
