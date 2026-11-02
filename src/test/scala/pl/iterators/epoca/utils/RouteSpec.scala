package pl.iterators.epoca.utils

import _root_.json.Schema
import _root_.json.schema.Predef
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.IORuntime
import com.github.andyglow.json.Value
import io.circe.parser.parse
import io.circe.{Json => CirceJson}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.headers.{HttpChallenge, HttpCredentials}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, RequestEntity, StatusCode}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.AuthenticationResult
import org.apache.pekko.http.scaladsl.testkit.{RouteTestTimeout, Specs2RouteTest}
import org.apache.pekko.testkit.TestKit
import org.mockito.stubbing.DefaultAnswer
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.specs2.matcher.MatchResult
import org.specs2.mutable.{After, SpecificationLike}
import org.specs2.specification.Scope
import pl.iterators.baklava.circe.CirceJsonStringProvider
import pl.iterators.baklava.fetcher.Specs2RouteBaklavaSpec
import pl.iterators.baklava.pekkohttp.PekkoHttpRouteBaklavaSpec
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.routers.UserAuthenticator
import pl.iterators.epoca.main.ApplicationLoader
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthProjectAccess, OAuthScope}
import pl.iterators.epoca.oauth.services.OAuthClientManagementService.OAuthClientVersionRequest
import pl.iterators.epoca.user.domain.UserDomain.UserAccountType
import pl.iterators.epoca.utils.ce._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.job.TriggeredJobScheduler
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.kebs.core.macros.CaseClass1ToValueClass
import pl.iterators.kebs.jsonschema.KebsJsonSchemaPredefs
import pl.iterators.kebs.scalacheck.KebsArbitrarySupport
import pl.iterators.kebs.tagged._
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import java.time.YearMonth
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.WeakTypeTag

trait RouteSpec
    extends Specs2RouteTest
    with SpecificationLike
    with MockitoSugar
    with ArgumentMatchersSugar
    with CirceJsonStringProvider
    with JsonProtocol
    with Generators
    with CatsEffect {

  type Json = CirceJson
  val emptyString = ""
  val emptyJson   = "{}"

  implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(5.seconds)

  def requestJson(jsonString: String): RequestEntity = HttpEntity(ContentTypes.`application/json`, jsonString)

  def io[T](value: T): IO[T] = IO.pure(value)

  implicit class JsonEqual(json: Json) {
    def shouldEqualJson(jsonString2: String): MatchResult[Any] =
      json shouldEqual parse(jsonString2).getOrElse(sys.error("Unable to parse json"))
  }

  implicit class StatusCodeCheck(statusCode: StatusCode) {
    def shouldBeInaccessible: MatchResult[Int] = {
      val code = statusCode.intValue()
      code should beBetween(400, 405)
    }
  }

  private lazy val _runtime: IORuntime = IORuntime.global
  override def createActorSystem(): ActorSystem =
    startActorSystemUntyped("test", None, true, Duration(1, "seconds"), Duration(1, "seconds")).allocated.unsafeRunSync()(_runtime)._1

  private lazy val _executor: ExecutionContext                   = _runtime.compute
  private lazy val _httpClient                                   = HttpClientFs2Backend.resource[IO]().allocated.unsafeRunSync()(_runtime)._1
  private lazy val _transactor: PostgresIOTransactor             = mock[PostgresIOTransactor]
  private lazy val _triggeredJobScheduler: TriggeredJobScheduler = mock[TriggeredJobScheduler]
  abstract class BaseScope
      extends ApplicationLoader(
        testConfig,
        _executor,
        _runtime,
        _transactor,
        _httpClient,
        _triggeredJobScheduler,
        mock[cats.effect.std.Supervisor[IO]]
      )
      with Scope
      with After {
    lazy val allRoutes: Route = Route.seal(routes)

    override lazy val pathPrefixMatcher = ""
    override lazy val userAuthenticator: UserAuthenticator = (_: Option[HttpCredentials]) =>
      Future.successful(
        authContextForTest
          .map(AuthenticationResult.success)
          .getOrElse(AuthenticationResult.failWithChallenge(HttpChallenge(scheme = "Bearer", realm = "realm")))
      )

    val authContextForTest: Option[AuthContext]
    val _allMocks: scala.collection.mutable.ListBuffer[AnyRef] = ListBuffer.empty

    def vmock[T <: AnyRef: ClassTag: WeakTypeTag](implicit defaultAnswer: DefaultAnswer): T = {
      val newMock = mock[T](defaultAnswer)
      _allMocks.append(newMock)
      newMock
    }

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

    private def complete = 1 shouldEqual 1
  }

}

trait ProjectDefinedPredefs extends CaseClass1ToValueClass with KebsArbitrarySupport with KebsJsonSchemaPredefs {
  implicit def mapPredef[B1, T1, B2, T2](implicit schema: Predef[Map[B1, B2]]): Predef[Map[B1 @@ T1, B2 @@ T2]] =
    schema.asInstanceOf[Predef[Map[B1 @@ T1, B2 @@ T2]]]

  implicit override val predefYearMonth: Predef[YearMonth] = Predef(Schema.`string`[YearMonth])

  implicit val predefJsValue: Predef[CirceJson] = Predef(Schema.`object`.Free[CirceJson]())

  implicit val predefUserAccountType: Predef[UserAccountType] =
    Predef(Schema.`enum`(Schema.`string`, UserAccountType.values.map(value => Value.str(value.entryName)).toSet))

  implicit val predefOAuthScope: Predef[OAuthScope] =
    Predef(Schema.`enum`(Schema.`string`, OAuthScope.values.map(value => Value.str(value.value)).toSet))

  implicit val predefOAuthProjectAccess: Predef[OAuthProjectAccess] = {
    val mode = Schema.`enum`[String](Schema.`string`, Set(Value.str("AllProjects"), Value.str("SelectedProjects")))
    Predef(
      Schema.`object`[OAuthProjectAccess](
        Schema.`object`.Field("mode", mode),
        Schema.`object`
          .Field(
            "projectIds",
            Schema.`array`[java.util.UUID, Set](Schema.`string`[java.util.UUID](Schema.`string`.Format.`uuid`), unique = true),
            required = false
          )
      )
    )
  }

  implicit val predefOAuthClientVersionRequest: Predef[OAuthClientVersionRequest] =
    Predef(Schema.`object`[OAuthClientVersionRequest](Schema.`object`.Field("expectedVersion", Schema.`number`[Long])))
}

trait RouteDocSpec extends PekkoHttpRouteBaklavaSpec with Specs2RouteBaklavaSpec with ProjectDefinedPredefs with RouteSpec {

  override def shutdownSpec(): Unit = TestKit.shutdownActorSystem(system, verifySystemShutdown = false)

}
