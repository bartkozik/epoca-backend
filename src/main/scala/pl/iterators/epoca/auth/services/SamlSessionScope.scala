package pl.iterators.epoca.auth.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.{HttpMethods, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.auth.domain.{AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIsSuperAdmin}

/** Organization IdPs cannot confer global account authority, including through refresh or invitation completion. */
object SamlSessionScope extends Directives {
  def binding(context: AuthContext): Option[BoundSaml] = context.accountAuthentication.map(_.evidence).collect { case value: BoundSaml => value }

  def required(context: AuthContext): Boolean =
    binding(context).nonEmpty || context.authedBy.isEmpty || context.authedBy.exists(_.`type` == IdentityType.Saml)

  def restrict(context: AuthContext): AuthContext =
    if (!required(context)) context
    else
      context.copy(
        isSuperAdmin = UserIsSuperAdmin(false),
        roles = context.roles.filter(role => binding(context).exists(_.organizationId == role.organizationId)),
        authedBy = Set(AuthContextAuthedBy(IdentityType.Saml, None))
      )

  private def segments(path: Uri.Path): List[String] = path match {
    case Uri.Path.Empty               => Nil
    case Uri.Path.Slash(tail)         => segments(tail)
    case Uri.Path.Segment(head, tail) => head :: segments(tail)
  }

  private val credentialPaths = Set("login", "get-token", "token", "totp", "crypto", "firebase", "refresh-token")

  def guard(context: AuthContext, currentOrganization: => IO[Option[OrganizationSlug]])(routes: AuthContext => Route)(implicit runtime: IORuntime)
    : Route =
    if (!required(context)) routes(context)
    else
      extractRequest { request =>
        extractUnmatchedPath { path =>
          val parts = segments(path)
          val independentLogin = request.method == HttpMethods.POST && (parts match {
            case "auth" :: action :: Nil => credentialPaths.contains(action)
            case _                       => false
          })
          if (independentLogin) routes(restrict(context))
          else if (binding(context).isEmpty) complete(StatusCodes.Forbidden)
          else
            onSuccess(currentOrganization.unsafeToFuture()) {
              case Some(slug) =>
                val allowed = parts match {
                  case "organizations" :: requested :: _ => requested == slug.toString
                  case "organizations" :: Nil            => request.method == HttpMethods.GET
                  case "users" :: id :: Nil              => request.method == HttpMethods.GET && id == context.id.toString
                  case _                                 => false
                }
                if (allowed) routes(restrict(context)) else complete(StatusCodes.Forbidden)
              case None => complete(StatusCodes.Forbidden)
            }
        }
      }
}
