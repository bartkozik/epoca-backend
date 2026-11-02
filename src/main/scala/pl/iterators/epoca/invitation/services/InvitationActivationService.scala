package pl.iterators.epoca.invitation.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AccountAuthentication.{BoundSaml, Credential, CredentialMethod}
import pl.iterators.epoca.auth.domain.AuthDomain.{Jwt, PasswordHash, PasswordPlain}
import pl.iterators.epoca.auth.domain.{AccountAuthentication, AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.PasswordValidateService.{PasswordValidateProblem, PasswordValidateResult}
import pl.iterators.epoca.auth.services.{LoginResolveService, PasswordService, PasswordValidateService}
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.{Access, Record}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserAccountType, UserId}
import pl.iterators.epoca.user.domain.UserWithIdentities
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.{Clock, Instant}
import scala.concurrent.ExecutionContext
import scala.util.Try

object InvitationActivationService {
  sealed trait Result
  case object InvalidInvitation                                            extends Result
  case object Forbidden                                                    extends Result
  case object SignInRequired                                               extends Result
  case object IdentityMismatch                                             extends Result
  case object IdentityConflict                                             extends Result
  case object AlreadyMember                                                extends Result
  case object RevisionConflict                                             extends Result
  case object PasswordRequired                                             extends Result
  final case class InvalidPassword(problems: Set[PasswordValidateProblem]) extends Result
  final case class RateLimited(retryAfterSeconds: Long)                    extends Result
  final case class SessionIssueFailed(joined: Boolean = true)              extends Result
  final case class SessionJoined(
    organizationSlug: String,
    accessToken: Jwt,
    refreshToken: Jwt)
      extends Result
  private[epoca] final case class Activated(
    userId: UserId,
    organizationSlug: String,
    authentication: Option[AccountAuthentication],
    authedBy: Set[AuthContextAuthedBy],
    lastAuthedAt: Instant)
      extends Result
  private[invitation] final case class Rejected(result: Result)                             extends RuntimeException
  private final case class Ready(needsPassword: Boolean, identityConflict: Boolean = false) extends Result
  private[invitation] def fail[A](result: Result): DBIO[A] = DBIO.failed(Rejected(result))
  private[invitation] def proofOrganizations(auth: Option[AuthContext]): Vector[OrganizationId] =
    auth.toVector.flatMap(_.accountAuthentication.toVector.flatMap(_.evidence match {
      case b: BoundSaml => Vector(b.organizationId)
      case _            => Vector.empty
    }))
  private def revision(value: String): Option[Long] = Option.when(value.matches("0|[1-9][0-9]{0,18}"))(value).flatMap(v => Try(v.toLong).toOption)
  private[invitation] def recover(error: Throwable): Option[Result] = error match {
    case Rejected(result) => Some(result)
    case _                => None
  }
}

/** IO owns password work, independently committed throttling and post-commit sessions. The action is independently composable for trusted adapters.
  */
final class InvitationActivationService(
  transactor: PostgresIOTransactor,
  access: InvitationAccessService,
  actions: InvitationActivationActions,
  passwordService: PasswordService,
  passwordValidateService: PasswordValidateService,
  login: LoginResolveService,
  limits: InvitationRateLimitService,
  browser: InvitationBrowserSecurity,
  clock: Clock) {
  import InvitationActivationService._
  private implicit val ec: ExecutionContext = ExecutionContext.global

  def nextAction(cookie: String, auth: Option[AuthContext]): IO[Option[String]] = auth match {
    case None        => IO.pure(None)
    case Some(value) => transactor.executeTransactionally(nextActionAction(cookie, value)).recover { case Rejected(_) => None }
  }
  private[invitation] def nextActionAction(cookie: String, auth: AuthContext): DBIO[Option[String]] =
    access.withContext(
      cookie,
      allowCompleted = true,
      authenticatedCandidates = Vector(auth.id),
      updateUsers = true,
      proofOrganizations = proofOrganizations(Some(auth))
    )(DBIO.successful(Option.empty[String])) {
      (
        record,
        _,
        recipient
      ) =>
        recipient.resolution match {
          case Resolution.Single(user, _) if user.id == auth.id => actions.authenticateRecipient(record, auth).map(_ => Some("Accept"))
          case _                                                => DBIO.successful(None)
        }
    }

  def activate(
    cookie: String,
    expectedRevision: String,
    password: Option[PasswordPlain],
    auth: Option[AuthContext],
    origin: Option[String],
    csrf: Option[String],
    requestContext: LoginHttpRequestContext
  ): IO[Result] = {
    val preliminary = access.withContext(cookie, allowCompleted = true, allowAmbiguous = true)(DBIO.successful[Result](InvalidInvitation)) {
      (
        record,
        context,
        recipient
      ) =>
        if (!browser.mutationAllowed(origin, csrf, context.csrfHash)) DBIO.successful(Forbidden)
        else if (context.completedAt.isEmpty && !revision(expectedRevision).contains(record.revision)) DBIO.successful(RevisionConflict)
        else if (recipient.resolution == Resolution.Ambiguous)
          actions.contextProof(record, context).map(valid => if (valid) Ready(needsPassword = false, identityConflict = true) else InvalidInvitation)
        else if (context.completedAt.isEmpty && recipient.resolution == Resolution.Empty)
          actions.currentSamlProof(record, context).map { proof =>
            if (proof.isDefined && context.proof.emailVerifiedAt.isEmpty) SignInRequired
            else if (proof.isEmpty && context.proof.emailVerifiedAt.isEmpty) InvalidInvitation
            else Ready(proof.isEmpty || (context.proof.emailVerifiedAt.isDefined && password.isDefined))
          }
        else DBIO.successful(Ready(needsPassword = false))
    }
    transactor.executeTransactionally(preliminary).flatMap {
      case Ready(needsPassword, identityConflict) =>
        limits.mutation(cookie).flatMap {
          case InvitationRateLimitService.Denied(retry)               => IO.pure(RateLimited(retry))
          case InvitationRateLimitService.Allowed if identityConflict => IO.pure(IdentityConflict)
          case InvitationRateLimitService.Allowed =>
            val prepared: IO[Either[Result, Option[PasswordHash]]] =
              if (!needsPassword) IO.pure(Right(None))
              else
                password match {
                  case None => IO.pure(Left(PasswordRequired))
                  case Some(value) =>
                    passwordValidateService.validate(value) match {
                      case PasswordValidateResult.Invalid(problems) => IO.pure(Left(InvalidPassword(problems)))
                      case PasswordValidateResult.Valid             => IO.blocking(Right(Some(passwordService.encrypt(value))))
                    }
                }
            prepared.flatMap {
              case Left(result) => IO.pure(result)
              case Right(hash) =>
                transactor
                  .executeTransactionally(activateAction(cookie, expectedRevision, auth, hash, origin, csrf))
                  .handleErrorWith(error => recover(error).fold(IO.raiseError[Result](error))(IO.pure))
                  .flatMap {
                    case joined: Activated => issueSession(cookie, joined, requestContext)
                    case result            => IO.pure(result)
                  }
            }
        }
      case result => IO.pure(result)
    }
  }

  private[epoca] def activateAction(
    cookie: String,
    expectedRevision: String,
    auth: Option[AuthContext],
    passwordHash: Option[PasswordHash],
    origin: Option[String],
    csrf: Option[String]
  ): DBIO[Result] = for {
    hint        <- access.contextHint(cookie)
    requestHint <- hint.fold[DBIO[Option[pl.iterators.epoca.saml.domain.SamlRequest]]](DBIO.successful(None))(actions.requestHint)
    extraOrganizations = requestHint.toVector.flatMap(_.accountAuthentication.toVector.flatMap(_.evidence match {
                           case b: BoundSaml => Vector(b.organizationId)
                           case _            => Vector.empty
                         }))
    organizationLocks = (proofOrganizations(auth) ++ extraOrganizations).distinct
    result <- access.withContext(
                cookie,
                allowCompleted = true,
                auth.toVector.map(_.id) ++ requestHint.flatMap(_.userId),
                updateUsers = true,
                organizationLocks,
                allowAmbiguous = true
              )(DBIO.successful[Result](InvalidInvitation)) {
                (
                  record,
                  context,
                  recipient
                ) =>
                  if (!browser.mutationAllowed(origin, csrf, context.csrfHash)) DBIO.successful(Forbidden)
                  else if (context.completedAt.isDefined) replay(record, recipient.organization.slug, auth)
                  else if (!revision(expectedRevision).contains(record.revision)) DBIO.successful(RevisionConflict)
                  else
                    actions.contextProof(record, context).flatMap {
                      case false => DBIO.successful(InvalidInvitation)
                      case true =>
                        for {
                          saml <- actions.currentSamlProof(record, context)
                          serverAuth <-
                            if (saml.isDefined)
                              actions
                                .serverAuthentication(context, recipient.lockedUserIds, organizationLocks.toSet + record.invitation.organizationId)
                            else DBIO.successful(None)
                          _ <- if (auth.isDefined && serverAuth.exists(a => a.id != auth.get.id)) fail(IdentityMismatch) else DBIO.successful(())
                          verified <- resolveAccount(record, context, recipient.resolution, auth.orElse(serverAuth), passwordHash, saml)
                          (userId, evidence, methods, at) = verified
                          _ <- saml.fold[DBIO[Unit]](DBIO.successful(()))(p => actions.bindSaml(record, p, userId))
                          _ <- actions.join(record, userId)
                          _ <- actions.complete(context, userId)
                        } yield Activated(userId, recipient.organization.slug, evidence, methods, at)
                    }
              }
  } yield result

  private type VerifiedAccount = (UserId, Option[AccountAuthentication], Set[AuthContextAuthedBy], Instant)
  private def resolvedAuth(value: AuthContext): VerifiedAccount =
    (value.id, value.accountAuthentication, value.authedBy, value.accountAuthentication.get.authenticatedAt)

  private def resolveAccount(
    record: Record,
    context: Access,
    resolution: Resolution,
    auth: Option[AuthContext],
    passwordHash: Option[PasswordHash],
    saml: Option[pl.iterators.epoca.invitation.domain.InvitationOnboarding.VerifiedSamlProof]
  ): DBIO[VerifiedAccount] = resolution match {
    case Resolution.Empty if auth.isDefined => fail(IdentityMismatch)
    case Resolution.Empty =>
      for {
        _       <- if (context.proof.emailVerifiedAt.exists(at => !at.isAfter(clock.instant()))) DBIO.successful(()) else fail(SignInRequired)
        binding <- saml.fold[DBIO[Option[pl.iterators.epoca.saml.domain.SamlIdentityBinding]]](DBIO.successful(None))(actions.samlBinding)
        _       <- if (binding.isDefined || context.verifiedUserId.isDefined) fail(IdentityConflict) else DBIO.successful(())
        result <- saml match {
                    case Some(p) if passwordHash.isEmpty || context.proof.emailVerifiedAt.isEmpty =>
                      actions.createSamlAccount(record).map { identity =>
                        val proof = AccountAuthentication(p.verifiedAt, BoundSaml(p.organizationId, p.trustEpoch, p.issuer, p.subject))
                        (identity.userId, Some(proof), Set(AuthContextAuthedBy(IdentityType.Saml, Some(identity.id))), proof.authenticatedAt)
                      }
                    case _ if context.proof.emailVerifiedAt.isEmpty => fail(InvalidInvitation)
                    case _ =>
                      passwordHash match {
                        case None => fail(PasswordRequired)
                        case Some(hash) =>
                          actions.createAccount(record, hash).map { identity =>
                            val proof = AccountAuthentication(clock.instant(), Credential(CredentialMethod.Password, identity.id))
                            (
                              identity.userId,
                              Some(proof),
                              Set(AuthContextAuthedBy(IdentityType.MailAddress, Some(identity.id))),
                              proof.authenticatedAt
                            )
                          }
                      }
                  }
      } yield result
    case Resolution.Single(user, _) if user.archivedAt.isDefined || user.accountType != UserAccountType.Human => fail(IdentityConflict)
    case Resolution.Single(user, _) =>
      auth match {
        case Some(value) if value.id != user.id => fail(IdentityMismatch)
        case Some(value)                        => actions.authenticateRecipient(record, value).map(_ => resolvedAuth(value))
        case None                               => fail(SignInRequired)
      }
    case Resolution.Ambiguous => fail(IdentityConflict)
  }

  private def replay(
    record: Record,
    slug: String,
    auth: Option[AuthContext]
  ): DBIO[Result] = {
    val userId = record.acceptedUserId.get
    if (auth.exists(_.id != userId)) DBIO.successful(IdentityMismatch)
    else
      actions.currentMember(record, userId).flatMap {
        case false => DBIO.successful(InvalidInvitation)
        case true =>
          auth match {
            case Some(value) =>
              actions.authenticationValid(record, value).map { valid =>
                if (valid) Activated(userId, slug, value.accountAuthentication, value.authedBy, value.accountAuthentication.get.authenticatedAt)
                else SignInRequired
              }
            case None => DBIO.successful(SignInRequired)
          }
      }
  }

  private def issueSession(
    cookie: String,
    joined: Activated,
    requestContext: LoginHttpRequestContext
  ): IO[Result] = {
    val current = access.withContext(cookie, allowCompleted = true)(DBIO.successful(Option.empty[(UserWithIdentities, Seq[OrganizationUser])])) {
      (
        record,
        context,
        _
      ) =>
        if (!record.acceptedUserId.contains(joined.userId) || context.completedAt.isEmpty) DBIO.successful(None)
        else actions.sessionState(record, joined.userId)
    }
    transactor
      .executeTransactionally(current)
      .flatMap {
        case None => IO.pure[Result](InvalidInvitation)
        case Some((user, memberships)) =>
          login
            .resolve(
              LoginResolveService.InvitationLogin(requestContext, user, memberships, joined.authentication, joined.authedBy, joined.lastAuthedAt)
            )
            .map(tokens => SessionJoined(joined.organizationSlug, tokens.accessToken, tokens.refreshToken): Result)
      }
      .handleError(_ => SessionIssueFailed())
  }
}
