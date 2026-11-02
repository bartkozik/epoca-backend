package pl.iterators.epoca.user.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.user.domain.UserDomain.UserIdentityId
import pl.iterators.epoca.user.domain.UserIdentityDto
import pl.iterators.epoca.user.services.UserIdentityConfirmService.{UserIdentityConfirmRequest, UserIdentityConfirmResult}
import pl.iterators.epoca.user.services.UserIdentityCreateService.{UserIdentityCreateRequest, UserIdentityCreateResult}
import pl.iterators.epoca.user.services.UserIdentityDeleteService.UserIdentityDeleteResult
import pl.iterators.epoca.user.services.UserIdentityListService.UserIdentityListResult
import pl.iterators.epoca.user.services.UserIdentitySetMainService.UserIdentitySetMainResult
import pl.iterators.epoca.user.services._
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class UserIdentityRouter(
  userIdentityConfirmService: UserIdentityConfirmService,
  userIdentityCreateService: UserIdentityCreateService,
  userIdentityDeleteService: UserIdentityDeleteService,
  userIdentityListService: UserIdentityListService,
  userIdentitySetMainService: UserIdentitySetMainService
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import UserIdentityRouter._

  def routes(authContext: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(usersPath / identityPath) {
      (post & pathEndOrSingleSlash & entity(as[UserIdentityCreateRequest])) { request =>
        complete(userIdentityCreateService.create(authContext, request)) {
          case UserIdentityCreateResult.Ok(identity)                  => OK -> UserIdentityDto.from(identity)
          case UserIdentityCreateResult.InvalidRequest                => BadRequest
          case r @ UserIdentityCreateResult.IdentityNotFresh          => error(BadRequest, typeFromResult[r.type], "Current identity is not fresh")
          case r @ UserIdentityCreateResult.IdentityValueAlreadyExist => error(Conflict, typeFromResult[r.type], "Identity value already exist")
          case r @ UserIdentityCreateResult.NoMainIdentity            => error(Conflict, typeFromResult[r.type], "No main identity")
        }
      } ~ (get & pathEndOrSingleSlash) {
        complete(userIdentityListService.list(authContext)) { case UserIdentityListResult.Ok(identitiesDto) =>
          OK -> identitiesDto
        }
      } ~ pathPrefix(JavaUUID.as[UserIdentityId]) { identityId =>
        (patch & pathEndOrSingleSlash & entity(as[UserIdentityConfirmRequest])) { request =>
          complete(userIdentityConfirmService.confirm(authContext, identityId, request)) {
            case UserIdentityConfirmResult.Ok(identityDto)      => OK -> identityDto
            case r @ UserIdentityConfirmResult.InvalidCode      => error(Conflict, typeFromResult[r.type], "Invalid code")
            case r @ UserIdentityConfirmResult.IdentityNotFound => error(NotFound, typeFromResult[r.type], "Identity not found")
          }
        } ~ (delete & pathEndOrSingleSlash) {
          complete(userIdentityDeleteService.delete(authContext, identityId)) {
            case UserIdentityDeleteResult.Ok                   => NoContent
            case r @ UserIdentityDeleteResult.IdentityNotFound => error(NotFound, typeFromResult[r.type], "Identity not found")
            case r @ UserIdentityDeleteResult.CanNotDeleteMain => error(Conflict, typeFromResult[r.type], "Cannot delete main identity")
            case r @ UserIdentityDeleteResult.IdentityNotFresh => error(BadRequest, typeFromResult[r.type], "Current identity is not fresh")
          }
        } ~ (post & path(mainPath) & pathEndOrSingleSlash) {
          complete(userIdentitySetMainService.setMain(authContext, identityId)) {
            case UserIdentitySetMainResult.Ok(identityDto)      => OK -> identityDto
            case UserIdentitySetMainResult.AlreadyMain          => NoContent
            case r @ UserIdentitySetMainResult.IdentityNotFound => error(NotFound, typeFromResult[r.type], "Identity not found")
          }
        }
      }
    }
}

object UserIdentityRouter {
  private val usersPath    = "users"
  private val identityPath = "identities"
  private val mainPath     = "main"
}
