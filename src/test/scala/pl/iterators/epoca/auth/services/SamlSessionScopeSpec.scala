package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.auth.domain.AuthContext.OrganizationRoles
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug, OrganizationUserRole}
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIsSuperAdmin}
import pl.iterators.epoca.utils.{AuthContextProvider, RouteSpec}

import java.time.Instant
import java.util.UUID

class SamlSessionScopeSpec extends RouteSpec {
  implicit val runtime: IORuntime = IORuntime.global
  private val organization        = OrganizationId(UUID.randomUUID())
  private val other               = OrganizationId(UUID.randomUUID())
  private val scoped = AuthContextProvider.user.copy(
    isSuperAdmin = UserIsSuperAdmin(true),
    authedBy = Set(AuthContextAuthedBy(IdentityType.Saml, None)),
    roles = Seq(OrganizationRoles(organization, Seq(OrganizationUserRole.Owner)), OrganizationRoles(other, Seq(OrganizationUserRole.Owner))),
    accountAuthentication = Some(AccountAuthentication(Instant.now(), BoundSaml(organization, 1L, "issuer", "subject")))
  )
  private def guarded(context: AuthContext, current: Option[OrganizationSlug] = Some(OrganizationSlug("acme"))): Route =
    Route.seal(pathPrefix("api" / "v1") {
      SamlSessionScope.guard(context, IO.pure(current)) { safe =>
        complete(s"${safe.isAdmin}:${safe.roles.map(_.organizationId).mkString(",")}")
      }
    })

  "SAML request scope" should {
    "admit own organization routes with normalized privileges" in {
      Get("/api/v1/organizations/acme/projects") ~> guarded(scoped) ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual s"false:$organization"
      }
    }
    "deny foreign organizations, global mutation and invitation completion before dispatch" in {
      Seq(
        Get("/api/v1/organizations/other/projects"),
        Get("/api/v1/organizations/acme-other/projects"),
        Get("/api/v1/organizations/acme%2Fother/projects"),
        Post("/api/v1/organizations"),
        Patch(s"/api/v1/users/${scoped.id}"),
        Post("/api/v1/users/identities"),
        Get("/api/v1/auth/totp"),
        Patch("/api/v1/auth/password"),
        Post("/api/v1/auth/admin"),
        Post("/api/v1/invitation-access/accept"),
        Get("/api/v1/auth/refresh-tokens")
      ).foreach(request => request ~> guarded(scoped) ~> check(status shouldEqual StatusCodes.Forbidden))
      success
    }
    "allow scoped organization discovery and own profile read" in {
      Seq(Get("/api/v1/organizations"), Get(s"/api/v1/users/${scoped.id}")).foreach { request =>
        request ~> guarded(scoped) ~> check(status shouldEqual StatusCodes.OK)
      }
      success
    }
    "deny a revoked trust or membership and legacy SAML evidence" in {
      Get("/api/v1/organizations/acme/projects") ~> guarded(scoped, None) ~> check(status shouldEqual StatusCodes.Forbidden)
      Get("/api/v1/organizations/acme/projects") ~> guarded(scoped.copy(accountAuthentication = None)) ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }
    "allow independent credential authentication after IdP revocation" in {
      Seq("login", "get-token", "token").foreach { action =>
        Post(s"/api/v1/auth/$action") ~> guarded(scoped, None) ~> check(status shouldEqual StatusCodes.OK)
      }
      success
    }
    "never evaluate SAML persistence for independent credential sessions" in {
      val auth   = AuthContextProvider.user
      val routes = SamlSessionScope.guard(auth, IO.raiseError(new AssertionError("unexpected trust lookup")))(_ => complete("ordinary"))
      Get("/organizations/other") ~> routes ~> check(responseAs[String] shouldEqual "ordinary")
    }
    "deny legacy refreshed SAML sessions with no authentication marker or proof" in {
      Get("/api/v1/organizations/acme/projects") ~> guarded(scoped.copy(authedBy = Set.empty, accountAuthentication = None)) ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }
  }
}
