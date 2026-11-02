package pl.iterators.epoca.utils

import cats.effect.Clock
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.IORuntime
import com.softwaremill.macwire.MacwireMacros
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.{DefaultAnswer, ScalaFirstStubbing, ScalaOngoingStubbing}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.specs2.execute.ResultLike
import org.specs2.matcher.MatchResult
import org.specs2.mutable.{After, SpecificationLike}
import org.specs2.specification.Scope
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.utils.db.{DBIOInstances, PostgresIOTransactor}
import pl.iterators.epoca.utils.transactor.{FakeTransactor, RecordingTransactor, TransactorCall}
import slick.dbio.DBIO

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.WeakTypeTag

trait ServiceSpec extends SpecificationLike with MockitoSugar with ArgumentMatchersSugar with Generators with CatsEffect {

  implicit val ec: ExecutionContext                    = scala.concurrent.ExecutionContext.Implicits.global
  implicit val ioRuntime: IORuntime                    = cats.effect.unsafe.implicits.global
  implicit val dbioApplicative: cats.Applicative[DBIO] = DBIOInstances.dbioInstance(ec)

  trait BaseScope extends Scope with After with ProjectSpecificScope {
    def wire[T]: T = macro MacwireMacros.wire_impl[T]
    type IO[A]   = cats.effect.IO[A]
    type DBIO[A] = slick.dbio.DBIO[A]

    implicit class AsDBIO[T](t: T) {
      def asDBIO: DBIO[T] = slick.dbio.DBIO.successful(t)
    }
    implicit class AsFuture[T](t: T) {
      def asFuture: Future[T] = Future.successful(t)
    }
    implicit class AsIO[T](t: T) {
      def asIO: IO[T] = cats.effect.IO.pure(t)
    }

    implicit class EnrichedScalaFirstStubbingDBIO[T](stub: ScalaFirstStubbing[DBIO[T]]) {
      def thenAnswerArgAsDBIO(argPosition: Int = 0): ScalaOngoingStubbing[DBIO[T]] =
        stub.thenAnswer { (invocation: InvocationOnMock) =>
          invocation.getArguments()(argPosition).asInstanceOf[T].asDBIO
        }
    }

    implicit class EnrichedScalaFirstStubbingIO[T](stub: ScalaFirstStubbing[IO[T]]) {
      def thenAnswerArgAsDBIO(argPosition: Int = 0): ScalaOngoingStubbing[IO[T]] =
        stub.thenAnswer { (invocation: InvocationOnMock) =>
          invocation.getArguments()(argPosition).asInstanceOf[T].asIO
        }
    }

    implicit class EnrichedScalaFirstStubbingFuture[T](stub: ScalaFirstStubbing[Future[T]]) {
      def thenAnswerArgAsDBIO(argPosition: Int = 0): ScalaOngoingStubbing[Future[T]] =
        stub.thenAnswer { (invocation: InvocationOnMock) =>
          invocation.getArguments()(argPosition).asInstanceOf[T].asFuture
        }
    }

    private val memo: scala.collection.mutable.Map[IO[Any], Any] = scala.collection.mutable.Map.empty
    implicit class AssertingIO[T](io: IO[T]) {
      def asserting(assertion: T => ResultLike): ResultLike =
        assertion(memo.getOrElseUpdate(io, io.unsafeRunSync()).asInstanceOf[T])
    }

    val transactor: PostgresIOTransactor = new FakeTransactor() with RecordingTransactor
    def transactorCalls: Seq[TransactorCall] = transactor match {
      case recording: RecordingTransactor => recording.calls
      case _                              => Nil
    }

    val _allMocks: scala.collection.mutable.ListBuffer[AnyRef] = ListBuffer.empty
    def vmock[T <: AnyRef: ClassTag: WeakTypeTag](implicit defaultAnswer: DefaultAnswer): T = {
      val newMock = mock[T](defaultAnswer)
      _allMocks.addOne(newMock)
      newMock
    }

    private def complete = 1 shouldEqual 1

    override def delayedInit(x: => Unit): Unit =
      try {
        val res = x
        after
        res
      } finally ()

    override def after: MatchResult[Any] = {
      if (_allMocks.nonEmpty) verifyNoMoreInteractions(_allMocks.toList: _*)
      complete
    }
  }

  trait FixedTimeBaseScope extends BaseScope {
    val now: Instant                             = Instant.now().minusSeconds(3600 * 2).truncatedTo(ChronoUnit.MILLIS)
    implicit val fixedTimeDBIOClock: Clock[DBIO] = getFixedTimeClock[DBIO]
    implicit val fixedTimeIOClock: Clock[IO]     = getFixedTimeClock[IO]
    def getFixedTimeClock[F[_]: cats.Applicative]: Clock[F] =
      new Clock[F] {
        override def monotonic: F[FiniteDuration] =
          cats.Applicative[F].pure(FiniteDuration(now.toEpochMilli, TimeUnit.MILLISECONDS))
        override def realTime: F[FiniteDuration] =
          cats.Applicative[F].pure(FiniteDuration(now.toEpochMilli, TimeUnit.MILLISECONDS))

        override def applicative: cats.Applicative[F] = cats.Applicative[F]
      }
  }

  trait ProjectSpecificScope {
    val authUser: AuthContext  = AuthContextProvider.user
    val authAdmin: AuthContext = AuthContextProvider.admin
  }

}
