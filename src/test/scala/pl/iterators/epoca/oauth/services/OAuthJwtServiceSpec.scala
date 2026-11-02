package pl.iterators.epoca.oauth.services

import io.circe.Json
import io.circe.parser.parse
import org.specs2.mutable.SpecificationLike
import pdi.jwt.{Jwt, JwtAlgorithm, JwtHeader}
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientPublicId, OAuthJwtError, OAuthScope, OAuthTokenClaims}
import pl.iterators.epoca.oauth.services.OAuthConfig.{OAuthSigningKey, OAuthSigningKeyRing}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, UUID}

class OAuthJwtServiceSpec extends SpecificationLike {
  private val issuer        = "https://api.epoca.example/oauth"
  private val audience      = "epoca-api"
  private val activeKid     = "oauth-2026-08-a"
  private val overlapKid    = "oauth-2026-08-b"
  private val activeSecret  = "active-oauth-signing-key-material-32-bytes-minimum"
  private val overlapSecret = "overlap-oauth-signing-key-material-32-bytes-minimum"
  private val keyRing = OAuthSigningKeyRing(
    activeKid,
    Vector(OAuthSigningKey(activeKid, activeSecret, retired = false), OAuthSigningKey(overlapKid, overlapSecret, retired = false))
  )
  private val service  = new OAuthJwtService(issuer, audience, keyRing)
  private val issuedAt = Instant.parse("2026-08-13T10:15:30Z")
  private val claims = OAuthTokenClaims(
    issuer,
    audience,
    UserId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
    OAuthClientPublicId("epoca_oauth_client_v1_AAAAAAAAAAAAAAAAAAAAAA"),
    OrganizationId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
    Set(OAuthScope.ReportsRead, OAuthScope.ProjectsRead),
    policyVersion = 7L,
    UUID.fromString("33333333-3333-3333-3333-333333333333"),
    issuedAt,
    issuedAt.plusSeconds(300)
  )

  "OAuth JWT" should {
    "sign with the active HS256 key and exact at+jwt header and claims" in {
      val token        = service.sign(claims).toOption.get
      val parts        = token.split("\\.")
      val header       = decodeJson(parts(0))
      val payload      = decodeJson(parts(1))
      val payloadNames = payload.asObject.toSeq.flatMap(_.keys).toSet

      header shouldEqual Json.obj("typ" -> Json.fromString("at+jwt"), "alg" -> Json.fromString("HS256"), "kid" -> Json.fromString(activeKid))
      payloadNames shouldEqual Set("iss", "aud", "sub", "client_id", "organization_id", "scope", "policy_version", "jti", "iat", "exp")
      payload.hcursor.get[String]("scope") shouldEqual Right("projects:read reports:read")
      payload.hcursor.get[Long]("iat") shouldEqual Right(issuedAt.getEpochSecond)
      payload.hcursor.get[Long]("exp") shouldEqual Right(issuedAt.plusSeconds(300).getEpochSecond)
      payload.noSpaces must not(contain("role"))
      payload.noSpaces must not(contain("project_id"))
      payload.noSpaces must not(contain("secret"))
    }

    "verify configured overlap keys while only the active key signs" in {
      val overlapToken = rawToken(claimJson(claims), overlapKid, overlapSecret)

      service.verify(service.sign(claims).toOption.get, issuedAt.plusSeconds(1)) shouldEqual Right(claims)
      service.verify(overlapToken, issuedAt.plusSeconds(1)) shouldEqual Right(claims)
    }

    "enforce the exact authoritative half-open temporal interval with zero leeway" in {
      val token = service.sign(claims).toOption.get

      service.verify(token, claims.expiresAt.minusNanos(1)) should beRight
      service.verify(token, claims.expiresAt) shouldEqual Left(OAuthJwtError.Expired)
      service.verify(token, claims.issuedAt.minusNanos(1)) shouldEqual Left(OAuthJwtError.NotYetValid)
    }

    "reject unknown and retired kids" in {
      val unknown = rawToken(claimJson(claims), "unknown", activeSecret)
      val retiredService = new OAuthJwtService(
        issuer,
        audience,
        OAuthSigningKeyRing(
          activeKid,
          Vector(OAuthSigningKey(activeKid, activeSecret, retired = false), OAuthSigningKey(overlapKid, overlapSecret, retired = true))
        )
      )
      val retired = rawToken(claimJson(claims), overlapKid, overlapSecret)

      service.verify(unknown, issuedAt.plusSeconds(1)) shouldEqual Left(OAuthJwtError.UnknownKey)
      retiredService.verify(retired, issuedAt.plusSeconds(1)) shouldEqual Left(OAuthJwtError.UnknownKey)
    }

    "fail closed for wrong algorithm, typ, issuer, audience, lifetime, policy version, scopes, and incompatible claim shape" in {
      val validJson = claimJson(claims)

      service.verify(rawToken(validJson, activeKid, activeSecret, JwtAlgorithm.HS384), issuedAt.plusSeconds(1)) shouldEqual Left(
        OAuthJwtError.InvalidToken
      )
      service.verify(rawToken(validJson, activeKid, activeSecret, typ = "JWT"), issuedAt.plusSeconds(1)) shouldEqual Left(OAuthJwtError.InvalidToken)
      service.verify(
        rawToken(validJson.deepMerge(Json.obj("iss" -> Json.fromString("https://wrong.example"))), activeKid, activeSecret),
        issuedAt.plusSeconds(1)
      ) shouldEqual Left(OAuthJwtError.InvalidToken)
      service.verify(
        rawToken(validJson.deepMerge(Json.obj("aud" -> Json.fromString("wrong"))), activeKid, activeSecret),
        issuedAt.plusSeconds(1)
      ) shouldEqual Left(OAuthJwtError.InvalidToken)
      service.verify(
        rawToken(validJson.deepMerge(Json.obj("exp" -> Json.fromLong(issuedAt.plusSeconds(301).getEpochSecond))), activeKid, activeSecret),
        issuedAt.plusSeconds(1)
      ) shouldEqual Left(OAuthJwtError.InvalidToken)
      service.verify(
        rawToken(validJson.deepMerge(Json.obj("policy_version" -> Json.fromLong(0))), activeKid, activeSecret),
        issuedAt.plusSeconds(1)
      ) shouldEqual Left(OAuthJwtError.InvalidToken)
      service.verify(
        rawToken(validJson.deepMerge(Json.obj("scope" -> Json.fromString("Projects:Read"))), activeKid, activeSecret),
        issuedAt.plusSeconds(1)
      ) shouldEqual Left(OAuthJwtError.InvalidToken)
      service.verify(
        rawToken(validJson.deepMerge(Json.obj("sub" -> Json.fromString("1-1-1-1-1"))), activeKid, activeSecret),
        issuedAt.plusSeconds(1)
      ) shouldEqual Left(OAuthJwtError.InvalidToken)
      service.verify(
        rawToken(validJson.deepMerge(Json.obj("roles" -> Json.arr())), activeKid, activeSecret),
        issuedAt.plusSeconds(1)
      ) shouldEqual Left(OAuthJwtError.InvalidToken)
    }
  }

  private def decodeJson(encoded: String): Json =
    parse(new String(Base64.getUrlDecoder.decode(encoded), StandardCharsets.UTF_8)).toOption.get

  private def claimJson(value: OAuthTokenClaims): Json = Json.obj(
    "iss"             -> Json.fromString(value.issuer),
    "aud"             -> Json.fromString(value.audience),
    "sub"             -> Json.fromString(value.subject.toString),
    "client_id"       -> Json.fromString(value.clientId.toString),
    "organization_id" -> Json.fromString(value.organizationId.toString),
    "scope"           -> Json.fromString(OAuthScopeService.render(value.scopes)),
    "policy_version"  -> Json.fromLong(value.policyVersion),
    "jti"             -> Json.fromString(value.tokenId.toString),
    "iat"             -> Json.fromLong(value.issuedAt.getEpochSecond),
    "exp"             -> Json.fromLong(value.expiresAt.getEpochSecond)
  )

  private def rawToken(
    payload: Json,
    kid: String,
    secret: String,
    algorithm: JwtAlgorithm = JwtAlgorithm.HS256,
    typ: String = "at+jwt"
  ): String = Jwt.encode(JwtHeader(algorithm).withType(typ).withKeyId(kid), pdi.jwt.JwtClaim(payload.noSpaces), secret)
}
