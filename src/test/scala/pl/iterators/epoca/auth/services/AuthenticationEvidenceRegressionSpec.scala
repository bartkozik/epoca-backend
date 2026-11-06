package pl.iterators.epoca.auth.services

import io.circe.Json
import io.circe.syntax._
import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.RefreshTokenValidForStrategy
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, AuthContextAuthedBy, RefreshToken}
import pl.iterators.epoca.auth.services.LoginResolveService.{AdminImpersonate, RefreshTokenExchange, SamlLogin, UserLogin}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{UserIdentity, UserIdentityDto}
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.json.JsonProtocol

import java.time.{ZoneOffset, ZonedDateTime}
import java.util.UUID
import scala.concurrent.duration._

class AuthenticationEvidenceRegressionSpec extends ServiceSpec with JsonProtocol {
  "authentication evidence" should {
    "preserve original database proof through refresh" in new FixedTimeBaseScope {
      val generated = allGenerators[RefreshTokenExchange].normal.generate
      val identity  = allGenerators[UserIdentity].normal.generate.copy(userId = generated.user.id)
      val exchange  = generated.copy(user = generated.user.copy(identities = Seq(identity)))
      val proof = Json.obj(
        "authenticatedAt" -> now.minusSeconds(240).asJson,
        "evidence" -> Json.obj(
          "Credential" -> Json.obj("method" -> Json.fromString("Password"), "identityId" -> identity.id.asJson)
        )
      )
      val token = exchange.refreshToken
        .copy(userId = exchange.user.id, accountAuthentication = None)
        .asJson
        .deepMerge(Json.obj("accountAuthentication" -> proof))
        .as[RefreshToken]
        .toOption
        .get
      val context = exchange.copy(refreshToken = token).toAuthContext(ZonedDateTime.ofInstant(now, ZoneOffset.UTC))
      context.asJson.hcursor.downField("accountAuthentication").focus shouldEqual Some(proof)
    }
    "accept legacy access and refresh tokens without account proof" in new BaseScope {
      authUser.asJson.mapObject(_.remove("accountAuthentication")).as[AuthContext].isRight shouldEqual true
      val token = allGenerators[RefreshToken].normal.generate
      token.asJson.mapObject(_.remove("accountAuthentication")).as[RefreshToken].isRight shouldEqual true
    }
    "mint new evidence from the verified method, never IdentityType or a previous account" in new FixedTimeBaseScope {
      val generated = allGenerators[UserLogin].normal.generate
      val at        = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
      IdentityType.values.foreach { identityType =>
        val login =
          generated.copy(authBy = generated.authBy.copy(userId = generated.user.id, `type` = identityType), prevAuthContext = Some(authAdmin))
        login.copy(verifiedMethod = Some(CredentialMethod.CryptoVerification)).toAuthContext(at).accountAuthentication shouldEqual
          Some(AccountAuthentication(now, Credential(CredentialMethod.CryptoVerification, login.authBy.id)))
        login.copy(verifiedMethod = None).toAuthContext(at).accountAuthentication shouldEqual None
        login
          .copy(verifiedMethod = Some(CredentialMethod.Token), authBy = login.authBy.copy(userId = authAdmin.id))
          .toAuthContext(at)
          .accountAuthentication shouldEqual None
        val expectedIdentityId = Option.when(identityType != IdentityType.Saml)(login.authBy.id)
        login.toAuthContext(at).authedBy shouldEqual Set(AuthContextAuthedBy(identityType, expectedIdentityId))
      }
    }
    "never mint account proof for legacy SAML or impersonation" in new FixedTimeBaseScope {
      val login = allGenerators[UserLogin].normal.generate
      val at    = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
      SamlLogin(login.requestContext, login.user, login.orgUsers).toAuthContext(at).accountAuthentication shouldEqual None
      AdminImpersonate(login.requestContext, login.user, login.orgUsers, java.time.Duration.ofMinutes(1))
        .toAuthContext(at)
        .accountAuthentication shouldEqual None
    }
    "retain bound-SAML kind and original age during refresh without transferring accounts" in new FixedTimeBaseScope {
      val exchange = allGenerators[RefreshTokenExchange].normal.generate
      val proof    = AccountAuthentication(now.minusSeconds(240), BoundSaml(OrganizationId(UUID.randomUUID()), 3L, "issuer", "subject"))
      val token    = exchange.refreshToken.copy(userId = exchange.user.id, accountAuthentication = Some(proof))
      val at       = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
      val context  = exchange.copy(refreshToken = token).toAuthContext(at)
      context.accountAuthentication shouldEqual Some(proof)
      val config = LoginResolveService.Config(1.minute, 2.minutes, RefreshTokenValidForStrategy.FromLatestUsage)
      LoginResolveService
        .buildRefreshToken(config, at, context, exchange.copy(refreshToken = token), token.value)
        .accountAuthentication shouldEqual Some(proof)
      exchange.copy(refreshToken = token.copy(accountAuthentication = None)).toAuthContext(at).accountAuthentication shouldEqual None
      exchange.copy(refreshToken = token.copy(userId = authAdmin.id)).toAuthContext(at).accountAuthentication shouldEqual None
    }
    "redact an existing TOTP secret from the public identity DTO" in new BaseScope {
      val identity = allGenerators[UserIdentity].normal.generate
        .copy(`type` = IdentityType.TimeBasedOneTimePassword, value = UserIdentityValue("JBSWY3DPEHPK3PXP"))
      UserIdentityDto.from(identity).value shouldEqual UserIdentityValue("")
      identity.value shouldEqual UserIdentityValue("JBSWY3DPEHPK3PXP")
    }
  }
}
