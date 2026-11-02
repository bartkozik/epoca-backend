package pl.iterators.epoca.auth.services

import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain._
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContextAuthedBy, RefreshToken}
import pl.iterators.epoca.auth.repositories.RefreshTokenRepository
import pl.iterators.epoca.auth.services.LoginResolveService._
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserAccountType, UserIsSuperAdmin}
import pl.iterators.epoca.user.domain.UserIdentity
import pl.iterators.epoca.utils.ServiceSpec

import java.time.{Duration => JavaDuration, ZoneId, ZonedDateTime}
import scala.concurrent.duration._

class LoginResolveSecuritySpec extends ServiceSpec {
  trait TestCase extends BaseScope with FixedTimeBaseScope {
    val repository: RefreshTokenRepository = mock[RefreshTokenRepository]
    val config: Config                     = Config(10.minutes, 30.days, RefreshTokenValidForStrategy.FromFirstUsage)
    val jwt                                = new JwtService(JwtService.Config("local-security-regression-secret"))
    val sut                                = new LoginResolveService(transactor, config, jwt, repository, new RefreshTokenValueGeneratorService)
    val base: AdminImpersonate             = allGenerators[AdminImpersonate].normal.generate
    val impersonation: AdminImpersonate = base.copy(user = base.user.copy(accountType = UserAccountType.Human), validFor = JavaDuration.ofMinutes(30))
    val at: ZonedDateTime               = ZonedDateTime.ofInstant(now, ZoneId.systemDefault())
    when(repository.upsert(any[RefreshToken])).thenAnswerArgAsDBIO()
  }

  "SAML issuance" should {
    Seq("login", "refresh", "invitation").foreach { kind =>
      s"preserve organization scope and remove super-admin privileges on $kind" in new TestCase {
        val user    = impersonation.user.copy(isSuperAdmin = UserIsSuperAdmin(true))
        val orgA    = allGenerators[OrganizationUser].normal.generate.copy(userId = user.id, archivedAt = None)
        val orgB    = allGenerators[OrganizationUser].normal.generate.copy(userId = user.id, archivedAt = None)
        val proof   = AccountAuthentication(now, BoundSaml(orgA.organizationId, 1L, "issuer", "subject"))
        val context = impersonation.requestContext
        val marker  = Set(AuthContextAuthedBy(IdentityType.Saml, None))
        val request: LoginResolveRequest = kind match {
          case "login"      => SamlLogin(context, user, Seq(orgA, orgB), Some(proof))
          case "invitation" => InvitationLogin(context, user, Seq(orgA, orgB), Some(proof), marker, now)
          case _ =>
            val token = allGenerators[RefreshToken].normal.generate.copy(userId = user.id, authedBy = Nil, accountAuthentication = Some(proof))
            RefreshTokenExchange(context, user, Seq(orgA, orgB), token)
        }
        val result = request.toAuthContext(at)
        result.isAdmin shouldEqual false
        result.roles.map(_.organizationId) shouldEqual Seq(orgA.organizationId)
        result.authedBy should contain(AuthContextAuthedBy(IdentityType.Saml, None))
      }
    }
    "allow independent password authentication to replace the earlier SAML scope" in new TestCase {
      val user     = impersonation.user.copy(isSuperAdmin = UserIsSuperAdmin(true))
      val orgA     = allGenerators[OrganizationUser].normal.generate.copy(userId = user.id, archivedAt = None)
      val orgB     = allGenerators[OrganizationUser].normal.generate.copy(userId = user.id, archivedAt = None)
      val identity = allGenerators[UserIdentity].normal.generate.copy(userId = user.id, `type` = IdentityType.MailAddress)
      val proof    = AccountAuthentication(now, BoundSaml(orgA.organizationId, 1L, "issuer", "subject"))
      val previous = SamlLogin(impersonation.requestContext, user, Seq(orgA, orgB), Some(proof)).toAuthContext(at)
      val result =
        UserLogin(impersonation.requestContext, user, Seq(orgA, orgB), Some(previous), identity, Some(CredentialMethod.Password)).toAuthContext(at)
      result.isAdmin shouldEqual true
      result.roles.map(_.organizationId) shouldEqual Seq(orgA.organizationId, orgB.organizationId)
      result.authedBy.map(_.`type`).contains(IdentityType.Saml) shouldEqual false
    }
  }

  "session resolution" should {
    "never persist a refresh token for impersonation" in new TestCase {
      sut.resolve(impersonation).unsafeRunSync()
      verifyNoInteractions(repository)
    }
    "deny invitation completion from impersonation instead of resetting its lifetime" in new TestCase {
      val auth    = impersonation.toAuthContext(at)
      val request = InvitationLogin(impersonation.requestContext, impersonation.user, impersonation.orgUsers, None, auth.authedBy, now)
      sut.resolve(request).attempt.asserting(_ shouldEqual Left(InvalidSession))
      verifyNoInteractions(repository)
    }
    "deny an unverified login from resetting impersonation authentication time" in new TestCase {
      val auth     = impersonation.toAuthContext(at)
      val identity = allGenerators[UserIdentity].normal.generate.copy(userId = impersonation.user.id)
      val request  = UserLogin(impersonation.requestContext, impersonation.user, impersonation.orgUsers, Some(auth), identity, None)
      sut.resolve(request).attempt.asserting(_ shouldEqual Left(InvalidSession))
      verifyNoInteractions(repository)
    }
    "reject excessive impersonation even when called without the HTTP service" in new TestCase {
      sut.resolve(impersonation.copy(validFor = JavaDuration.ofHours(2))).attempt.asserting(_.isLeft shouldEqual true)
      verifyNoInteractions(repository)
    }
    "reject legacy refresh sessions that lost their authentication type" in new TestCase {
      val refresh = allGenerators[RefreshTokenExchange].normal.generate
      val request = refresh.copy(
        user = refresh.user.copy(accountType = UserAccountType.Human),
        refreshToken = refresh.refreshToken
          .copy(userId = refresh.user.id, deviceId = refresh.requestContext.deviceId, authedBy = Nil, accountAuthentication = None)
      )
      sut.resolve(request).attempt.asserting(_.isLeft shouldEqual true)
      verifyNoInteractions(repository)
    }
    "anchor a legacy token deadline to its original creation time" in new TestCase {
      val refresh   = allGenerators[RefreshTokenExchange].normal.generate
      val createdAt = now.minusSeconds(86400)
      val request   = refresh.copy(refreshToken = refresh.refreshToken.copy(createdAt = RefreshTokenCreatedAt(createdAt), expiredAt = None))
      val token     = buildRefreshToken(config, at, impersonation.toAuthContext(at), request, RefreshTokenValue("replacement"))
      token.expiredAt shouldEqual Some(RefreshTokenExpiredAt(createdAt.plusSeconds(30L * 86400)))
    }
  }
}
