package pl.iterators.epoca.saml.services

import cats.effect.IO
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginResolveService
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, SamlLogin}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.domain.SamlDomain.SamlOneTimeToken
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport

class SamlLoginService(
  transactor: PostgresIOTransactor,
  samlInvitationService: SamlInvitationService,
  loginResolveService: LoginResolveService,
  invitationAccess: Option[pl.iterators.epoca.invitation.services.InvitationAccessService],
  workspaceAvatars: Option[WorkspaceAvatarSyncService] = None)
    extends LoggingSupport {
  import SamlLoginService._
  import SamlLoginResult._
  def login(
    request: SamlLoginRequest,
    organizationSlug: OrganizationSlug,
    requestContext: LoginHttpRequestContext,
    nonce: Option[String]
  ): IO[SamlLoginResult] =
    transactor.executeTransactionally(samlInvitationService.exchangeAction(request.token, organizationSlug, nonce)).flatMap {
      case SamlInvitationService.Denied => IO.pure(InvalidCallback)
      case SamlInvitationService.Continuation(slug, grant) =>
        IO.pure(
          invitationAccess.fold[SamlLoginResult](InvalidCallback)(access =>
            InvitationContinuation(slug, grant.dto.nextAction, access.cookieHeader(grant))
          )
        )
      case SamlInvitationService.Session(user, memberships, proof, at, photoProof) =>
        loginResolveService
          .resolve(SamlLogin(requestContext, user, memberships, proof, Some(at)))
          .flatTap { _ =>
            IO.defer(photoProof.fold(IO.unit)(photo => workspaceAvatars.fold(IO.unit)(_.schedule(user, photo))))
              .handleErrorWith(_ => Logger[IO].warning("Workspace photo dispatch failed"))
          }
          .map(LoggedIn)
    }
}
object SamlLoginService {
  case class SamlLoginRequest(token: SamlOneTimeToken) {
    override def toString: String = "SamlLoginRequest(<redacted>)"
  }
  case class InvitationResponse(
    kind: String,
    organizationSlug: String,
    nextAction: String)
  sealed trait SamlLoginResult
  object SamlLoginResult {
    case class LoggedIn(token: LoginResolution) extends SamlLoginResult
    case class InvitationContinuation(
      organizationSlug: String,
      nextAction: String,
      cookieHeader: String)
        extends SamlLoginResult {
      override def toString: String = "SamlLoginContinuation(<redacted>)"
    }
    case object InvalidCallback extends SamlLoginResult
  }
}
