package pl.iterators.epoca.user.routers

import cats.effect.unsafe.IORuntime
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.server.Route
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.UserRepository.{UserFilters, UserSortBy}
import pl.iterators.epoca.user.services.UserCreateService._
import pl.iterators.epoca.user.services.UserDeleteService.UserDeleteResult
import pl.iterators.epoca.user.services.UserFindService.UserFindResult
import pl.iterators.epoca.user.services.UserListService._
import pl.iterators.epoca.user.services.UserUpdateService._
import pl.iterators.epoca.user.services._
import pl.iterators.epoca.utils.http.BaseRouter
import pl.iterators.epoca.utils.log.TracingContext

class UserRouter(
  userCreateService: UserCreateService,
  userDeleteService: UserDeleteService,
  userFindService: UserFindService,
  userListService: UserListService,
  userUpdateService: UserUpdateService
)(implicit runtime: IORuntime)
    extends BaseRouter {
  import UserRouter._

  def routes(authContext: AuthContext)(implicit tracingContext: TracingContext): Route =
    pathPrefix(usersPath) {
      (get & pathEndOrSingleSlash & parameters("email".as[MailAddress].?, "phoneNumber".as[PhoneNumber].?, "sortBy".as[UserSortBy].?) & pagination) {
        (
          email,
          phoneNumber,
          sortBy,
          pagination
        ) =>
          val filters =
            UserFilters(email = email, phoneNumber = phoneNumber, pageSizeAndNumber = pagination, sortBy = sortBy)
          complete(userListService.listWithIdentities(authContext, filters)) {
            case UserListResult.Ok(users) => OK -> users
            case r @ UserListResult.OperationNotPermitted =>
              error(Forbidden, typeFromResult[r.type], "Operation not permitted", Some("User do not have access to user listing"))
          }
      } ~ pathPrefix(JavaUUID.as[UserId]) { id =>
        (get & pathEndOrSingleSlash) {
          complete(userFindService.find(authContext, id)) {
            case UserFindResult.Ok(data)           => OK -> data
            case r @ UserFindResult.UserDoNotExist => error(NotFound, typeFromResult[r.type], "User do not exist")
            case r @ UserFindResult.OperationNotPermitted =>
              error(Forbidden, typeFromResult[r.type], "Operation not permitted", Some("User do not have access to user listing"))
          }
        } ~ (patch & pathEndOrSingleSlash & entity(as[UserUpdateRequest])) { request =>
          complete(userUpdateService.update(authContext, id, request)) {
            case UserUpdateResult.Ok(data)           => OK -> data
            case r @ UserUpdateResult.UserDoNotExist => error(NotFound, typeFromResult[r.type], "User do not exist")
            case r @ UserUpdateResult.OperationNotPermitted =>
              error(Forbidden, typeFromResult[r.type], "Operation not permitted", Some("User do not have access to user updating"))
          }
        } ~ (delete & pathEndOrSingleSlash) {
          complete(userDeleteService.delete(authContext, id)) {
            case UserDeleteResult.Ok                 => NoContent
            case r @ UserDeleteResult.UserDoNotExist => error(NotFound, typeFromResult[r.type], "User do not exist")
            case r @ UserDeleteResult.OperationNotPermitted =>
              error(Forbidden, typeFromResult[r.type], "Operation not permitted", Some("User do not have access to user deleting"))
          }
        }
      }
    }

  def routes(implicit tracingContext: TracingContext): Route =
    pathPrefix(usersPath) {
      (post & pathEndOrSingleSlash & entity(as[UserCreateRequest])) { request =>
        complete(userCreateService.create(request)) {
          case UserCreateResult.Ok(user) => OK -> user
          case r @ UserCreateResult.InvalidPassword(problems) =>
            error(BadRequest, typeFromResult[r.type], "Invalid password", Some(problems.mkString(" ")), Map("problems" -> problems))
          case r @ UserCreateResult.InvalidRequest       => error(BadRequest, typeFromResult[r.type], "Invalid request")
          case r @ UserCreateResult.IdentityAlreadyExist => error(Conflict, typeFromResult[r.type], "Identity already exist")
          case r @ UserCreateResult.SuperAdminKeyRejected =>
            error(Forbidden, typeFromResult[r.type], "Operation not permitted", Some("Super admin key rejected"))
        }
      }
    }
}

object UserRouter {
  private val usersPath = "users"
}
