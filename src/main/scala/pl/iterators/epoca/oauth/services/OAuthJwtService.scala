package pl.iterators.epoca.oauth.services

import io.circe.parser.parse
import io.circe.{Json, JsonObject}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader, JwtOptions}
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthClientPublicId, OAuthJwtError, OAuthScope, OAuthTokenClaims}
import pl.iterators.epoca.oauth.services.OAuthConfig.OAuthSigningKeyRing
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.user.domain.UserDomain.UserId

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, UUID}
import scala.util.Try

trait OAuthTokenSigner {
  def sign(claims: OAuthTokenClaims): Either[OAuthJwtError, String]
}

final class OAuthJwtService(
  issuer: String,
  audience: String,
  keyRing: OAuthSigningKeyRing)
    extends OAuthTokenSigner {
  import OAuthJwtService._

  def sign(claims: OAuthTokenClaims): Either[OAuthJwtError, String] =
    for {
      key <- keyRing.activeKey.toRight(OAuthJwtError.UnknownKey)
      _   <- validateClaims(claims)
    } yield Jwt.encode(JwtHeader(JwtAlgorithm.HS256).withType(TokenType).withKeyId(key.kid), JwtClaim(claimsJson(claims).noSpaces), key.material)

  def verify(token: String, authoritativeNow: Instant): Either[OAuthJwtError, OAuthTokenClaims] =
    verifyClaims(token).flatMap(claims => validateAt(claims, authoritativeNow).map(_ => claims))

  def verifyClaims(token: String): Either[OAuthJwtError, OAuthTokenClaims] =
    for {
      compactParts <- splitCompact(token)
      headerJson   <- decodePart(compactParts.header)
      kid          <- validateHeader(headerJson)
      key          <- keyRing.verificationKeys.find(_.kid == kid).toRight(OAuthJwtError.UnknownKey)
      decoded      <- verifySignature(token, key.material)
      payloadJson  <- parse(decoded).left.map(_ => OAuthJwtError.InvalidToken)
      claims       <- decodeClaims(payloadJson)
      _            <- validateClaims(claims)
    } yield claims

  def validateAt(claims: OAuthTokenClaims, authoritativeNow: Instant): Either[OAuthJwtError, Unit] =
    validateAuthoritativeTime(claims, authoritativeNow)

  private def validateClaims(claims: OAuthTokenClaims): Either[OAuthJwtError, Unit] = {
    val secondsAligned = claims.issuedAt.getNano == 0 && claims.expiresAt.getNano == 0
    val lifetimeValid =
      Try(Math.subtractExact(claims.expiresAt.getEpochSecond, claims.issuedAt.getEpochSecond)).toOption.contains(TokenLifetimeSeconds)
    val scopeText = OAuthScopeService.render(claims.scopes)
    Either.cond(
      claims.issuer == issuer &&
        claims.audience == audience &&
        claims.clientId.toString.matches(OAuthSecretService.ClientIdPrefix + "[A-Za-z0-9_-]{22}") &&
        claims.policyVersion > 0L &&
        claims.scopes.nonEmpty &&
        parseScopes(scopeText).contains(claims.scopes) &&
        secondsAligned &&
        claims.issuedAt.getEpochSecond >= 0L &&
        lifetimeValid,
      (),
      OAuthJwtError.InvalidToken
    )
  }

  private def validateAuthoritativeTime(claims: OAuthTokenClaims, authoritativeNow: Instant): Either[OAuthJwtError, Unit] =
    if (authoritativeNow.isBefore(claims.issuedAt)) Left(OAuthJwtError.NotYetValid)
    else if (!authoritativeNow.isBefore(claims.expiresAt)) Left(OAuthJwtError.Expired)
    else Right(())

  private def validateHeader(json: Json): Either[OAuthJwtError, String] =
    json.asObject match {
      case Some(header) if header.keys.toSet == HeaderNames =>
        for {
          algorithm <- stringField(header, "alg")
          tokenType <- stringField(header, "typ")
          kid       <- stringField(header, "kid")
          _         <- Either.cond(algorithm == JwtAlgorithm.HS256.name && tokenType == TokenType && kid.nonEmpty, (), OAuthJwtError.InvalidToken)
        } yield kid
      case _ => Left(OAuthJwtError.InvalidToken)
    }

  private def decodeClaims(json: Json): Either[OAuthJwtError, OAuthTokenClaims] =
    json.asObject match {
      case Some(payload) if payload.keys.toSet == ClaimNames =>
        for {
          tokenIssuer    <- stringField(payload, "iss")
          tokenAudience  <- stringField(payload, "aud")
          subjectValue   <- uuidField(payload, "sub")
          clientId       <- stringField(payload, "client_id")
          organizationId <- uuidField(payload, "organization_id")
          scopeValue     <- stringField(payload, "scope")
          scopes         <- parseScopes(scopeValue)
          policyVersion  <- longField(payload, "policy_version")
          tokenId        <- uuidField(payload, "jti")
          issuedAt       <- longField(payload, "iat").flatMap(instant)
          expiresAt      <- longField(payload, "exp").flatMap(instant)
        } yield OAuthTokenClaims(
          tokenIssuer,
          tokenAudience,
          UserId(subjectValue),
          OAuthClientPublicId(clientId),
          OrganizationId(organizationId),
          scopes,
          policyVersion,
          tokenId,
          issuedAt,
          expiresAt
        )
      case _ => Left(OAuthJwtError.InvalidToken)
    }

  private def verifySignature(token: String, key: String): Either[OAuthJwtError, String] =
    Jwt
      .decodeRawAll(token, key, Seq(JwtAlgorithm.HS256), JwtOptions(signature = true, expiration = false, notBefore = false, leeway = 0L))
      .toEither
      .left
      .map(_ => OAuthJwtError.InvalidToken)
      .map(_._2)

  private def parseScopes(value: String): Either[OAuthJwtError, Set[OAuthScope]] = {
    val parsed = OAuthScopeService.resolveRequested(Some(value), OAuthScope.values.toSet)
    parsed.left
      .map(_ => OAuthJwtError.InvalidToken)
      .filterOrElse(scopes => scopes.nonEmpty && OAuthScopeService.render(scopes) == value, OAuthJwtError.InvalidToken)
  }

  private def splitCompact(token: String): Either[OAuthJwtError, CompactParts] =
    token.split("\\.", -1).toVector match {
      case Vector(header, payload, signature) if header.nonEmpty && payload.nonEmpty && signature.nonEmpty => Right(CompactParts(header))
      case _                                                                                               => Left(OAuthJwtError.InvalidToken)
    }

  private def decodePart(value: String): Either[OAuthJwtError, Json] =
    Try(new String(Base64.getUrlDecoder.decode(value), StandardCharsets.UTF_8)).toEither.left
      .map(_ => OAuthJwtError.InvalidToken)
      .flatMap(raw => parse(raw).left.map(_ => OAuthJwtError.InvalidToken))

  private def stringField(value: JsonObject, name: String): Either[OAuthJwtError, String] =
    value(name).flatMap(_.asString).filter(_.nonEmpty).toRight(OAuthJwtError.InvalidToken)

  private def longField(value: JsonObject, name: String): Either[OAuthJwtError, Long] =
    value(name).flatMap(_.asNumber).flatMap(_.toLong).toRight(OAuthJwtError.InvalidToken)

  private def uuidField(value: JsonObject, name: String): Either[OAuthJwtError, UUID] =
    stringField(value, name)
      .filterOrElse(raw => UuidPattern.pattern.matcher(raw).matches(), OAuthJwtError.InvalidToken)
      .flatMap(raw => Try(UUID.fromString(raw)).toEither.left.map(_ => OAuthJwtError.InvalidToken))

  private def instant(epochSecond: Long): Either[OAuthJwtError, Instant] =
    Try(Instant.ofEpochSecond(epochSecond)).toEither.left.map(_ => OAuthJwtError.InvalidToken)

  private def claimsJson(claims: OAuthTokenClaims): Json = Json.obj(
    "iss"             -> Json.fromString(claims.issuer),
    "aud"             -> Json.fromString(claims.audience),
    "sub"             -> Json.fromString(claims.subject.toString),
    "client_id"       -> Json.fromString(claims.clientId.toString),
    "organization_id" -> Json.fromString(claims.organizationId.toString),
    "scope"           -> Json.fromString(OAuthScopeService.render(claims.scopes)),
    "policy_version"  -> Json.fromLong(claims.policyVersion),
    "jti"             -> Json.fromString(claims.tokenId.toString),
    "iat"             -> Json.fromLong(claims.issuedAt.getEpochSecond),
    "exp"             -> Json.fromLong(claims.expiresAt.getEpochSecond)
  )
}

object OAuthJwtService {
  val TokenLifetimeSeconds: Long = 300L

  private val TokenType   = "at+jwt"
  private val UuidPattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r
  private val HeaderNames = Set("alg", "typ", "kid")
  private val ClaimNames  = Set("iss", "aud", "sub", "client_id", "organization_id", "scope", "policy_version", "jti", "iat", "exp")

  private final case class CompactParts(header: String)
}
