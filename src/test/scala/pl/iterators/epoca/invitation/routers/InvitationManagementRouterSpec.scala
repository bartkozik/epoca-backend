package pl.iterators.epoca.invitation.routers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import pl.iterators.epoca.invitation.services._
import pl.iterators.epoca.utils.log.TracingContext
import pl.iterators.epoca.utils.{AuthContextProvider, RouteSpec}

import java.util.UUID

class InvitationManagementRouterSpec extends RouteSpec {
  implicit val tracing: TracingContext = TracingContext("test", "test", Map.empty)
  "management API" should {
    "dispatch PATCH, resend, and revision cancellation without a version query" in {
      val management = mock[InvitationManagementService]
      val delivery   = mock[InvitationDeliveryService]
      val dto        = _root_.io.circe.Json.obj("revision" -> _root_.io.circe.Json.fromString("1"))
      when(management.patch(any, any, any, any)).thenReturn(IO.pure(InvitationManagementService.Ok(dto, None)))
      when(management.resend(any, any, any, any, any)).thenReturn(IO.pure(InvitationManagementService.Ok(dto, None)))
      when(management.cancel(any, any, any, any)).thenReturn(IO.pure(InvitationManagementService.RevisionConflict))
      when(delivery.afterCommit(any)).thenAnswer((command: IO[InvitationManagementService.Result]) => command)
      val route = new InvitationManagementRouter(management, delivery, true).authRoutes(AuthContextProvider.user)
      val path  = s"/organizations/acme/invitations/${UUID.randomUUID()}"
      Patch(path, requestJson("{\"expectedRevision\":\"0\"}")) ~> route ~> check(status shouldEqual StatusCodes.OK)
      Post(path + "/resend", requestJson("{\"expectedRevision\":\"0\"}")).withHeaders(
        RawHeader("Idempotency-Key", UUID.randomUUID().toString)
      ) ~> route ~> check(status shouldEqual StatusCodes.OK)
      Delete(path + "?expectedRevision=0") ~> route ~> check(status shouldEqual StatusCodes.Conflict)
      Delete(path + "?expectedRevision=bad") ~> route ~> check(status shouldEqual StatusCodes.BadRequest)
    }
    "keep disabled capability private from non-Owners" in {
      val management = mock[InvitationManagementService]
      when(management.authorize(any, any)).thenReturn(IO.pure(false))
      val route = new InvitationManagementRouter(management, mock[InvitationDeliveryService], false).authRoutes(AuthContextProvider.user)
      Post("/organizations/acme/invitations?version=2", requestJson("invalid")) ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Json].noSpaces.contains("feature-disabled") shouldEqual false
      }
    }

    "serialize only the safe DTO and dispatch delivery after commit" in {
      val management = mock[InvitationManagementService]
      val delivery   = mock[InvitationDeliveryService]
      val dto        = _root_.io.circe.Json.obj("revision" -> _root_.io.circe.Json.fromString("0"))
      when(management.create(any, any, any, any)).thenReturn(IO.pure(InvitationManagementService.Ok(dto, None)))
      when(delivery.afterCommit(any)).thenAnswer((command: IO[InvitationManagementService.Result]) => command)
      val router = new InvitationManagementRouter(management, delivery, true)
      Post("/organizations/acme/invitations?version=2", requestJson("{}"))
        .withHeaders(RawHeader("Idempotency-Key", UUID.randomUUID().toString)) ~> router.authRoutes(AuthContextProvider.user) ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqual dto
      }
    }
    "disable create before parsing the entity" in {
      val management = mock[InvitationManagementService]
      when(management.authorize(any, any)).thenReturn(IO.pure(true))
      val router = new InvitationManagementRouter(management, mock[InvitationDeliveryService], false)
      Post("/organizations/acme/invitations?version=2", requestJson("not-json")) ~> router.authRoutes(AuthContextProvider.user) ~> check {
        status shouldEqual StatusCodes.ServiceUnavailable
      }
    }
    "keep invitation sending disabled for an authorized superadmin" in {
      val management = mock[InvitationManagementService]
      when(management.authorize(any, any)).thenReturn(IO.pure(true))
      val router     = new InvitationManagementRouter(management, mock[InvitationDeliveryService], false)
      val collection = "/organizations/acme/invitations?version=2"
      val resend     = s"/organizations/acme/invitations/${UUID.randomUUID()}/resend?version=2"
      Seq(collection, resend).foreach { path =>
        Post(path, requestJson("not-json")) ~> router.authRoutes(AuthContextProvider.admin) ~> check {
          status shouldEqual StatusCodes.ServiceUnavailable
        }
      }
      verify(management, never).create(any, any, any, any)
      verify(management, never).resend(any, any, any, any, any)
      success
    }
    "reject unknown versions before parsing the entity" in new BaseScope {
      override val authContextForTest = Some(AuthContextProvider.user)
      Post("/organizations/acme/invitations?version=3", requestJson("not-json")) ~> allRoutes ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Json].noSpaces must contain("Unsupported invitation API version")
      }
    }
    "return Retry-After without serializing internal results" in {
      val management = mock[InvitationManagementService]
      val delivery   = mock[InvitationDeliveryService]
      when(management.resend(any, any, any, any, any)).thenReturn(IO.pure(InvitationManagementService.RateLimited(17)))
      when(delivery.afterCommit(any)).thenAnswer((command: IO[InvitationManagementService.Result]) => command)
      val router = new InvitationManagementRouter(management, delivery, true)
      Post(s"/organizations/acme/invitations/${UUID.randomUUID()}/resend?version=2", requestJson("{}"))
        .withHeaders(RawHeader("Idempotency-Key", UUID.randomUUID().toString)) ~> router.authRoutes(AuthContextProvider.user) ~> check {
        status shouldEqual StatusCodes.TooManyRequests
        header("Retry-After").map(_.value()) shouldEqual Some("17")
      }
    }
  }
}
