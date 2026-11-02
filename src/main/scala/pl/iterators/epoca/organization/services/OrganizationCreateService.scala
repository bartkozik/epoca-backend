package pl.iterators.epoca.organization.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import com.typesafe.config.{Config => TsConfig}
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationUserRole.Owner
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationCreateService.OrganizationCreateResult._
import pl.iterators.epoca.organization.services.OrganizationCreateService.{OrganizationCreateRequest, OrganizationCreateResult}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.random.IdGenerator
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._

import scala.util.Try

class OrganizationCreateService(
  config: OrganizationCreateService.Config,
  transactor: PostgresIOTransactor,
  userRepository: UserRepository,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationCreateResult](ioRuntime)
    with LoggingSupport {

  def create(authContext: AuthContext, request: OrganizationCreateRequest): IO[OrganizationCreateResult] =
    (for {
      _                 <- findHumanActor(authContext.id)
      newOrganizationId <- IdGenerator.nextId[IO, OrganizationId].seal
      _                 <- Logger[IO].info(s"auth.id=${authContext.id}").seal
      _                 <- checkPermission(authContext)
      _                 <- checkSlugUniqueness(request.slug)
      toInsert          <- prepareOrganization(authContext.id, request, newOrganizationId)
      organization      <- insertOrganizationWithOwner(authContext, toInsert)
    } yield OrganizationCreateResult.Created(organization)).run

  private def findHumanActor(userId: UserId): StepIO[Unit] =
    transactor
      .execute(userRepository.find(UserFilters(id = Some(userId))))
      .valueOr(OperationNotPermitted)
      .void

  private def checkPermission(authContext: AuthContext): StepIO[Unit] =
    if (authContext.isSuperAdmin) {
      Sealed.liftF(())
    } else {
      config.organizationLimitUserCanCreate
        .map { limit =>
          val query = organizationRepository.count(OrganizationFilters(creatorId = Some(authContext.id)))
          transactor
            .execute(query)
            .ensureOrF(
              organizationCount => organizationCount < limit,
              organizationCount =>
                Logger[IO]
                  .warning(s"user ${authContext.id} already created $limit organizations")
                  .as(UserOrganizationsLimitExceeded(limit, organizationCount))
            )
            .void
        }
        .getOrElse(Sealed.liftF(()))
    }

  private def checkSlugUniqueness(slug: OrganizationSlug): StepIO[Unit] = {
    val query = organizationRepository.find(OrganizationFilters(slug = Some(slug)))
    transactor
      .execute(query)
      .ensureF(_.isEmpty, Logger[IO].warning(s"Organization with slug $slug already exists.").as(SlugIsAlreadyTaken))
      .void
  }

  private def prepareOrganization(
    userId: UserId,
    request: OrganizationCreateRequest,
    organizationId: OrganizationId
  ): StepIO[Organization] =
    Clock[IO].instantNow.map { now =>
      request
        .into[Organization]
        .withFieldConst(_.id, organizationId)
        .withFieldConst(_.createdAt, OrganizationCreatedAt(now))
        .withFieldConst(_.createdBy, userId)
        .withFieldConst(_.modifiedAt, OrganizationModifiedAt(now))
        .withFieldConst(_.modifiedBy, userId)
        .withFieldConst(_.archivedAt, None)
        .transform
    }.seal

  private def insertOrganizationWithOwner(authContext: AuthContext, organization: Organization): StepIO[Organization] =
    transactor.executeTransactionally {
      for {
        inserted <- organizationRepository.insert(organization)
        orgUser = OrganizationUser(OrganizationUserId.random, inserted.id, authContext.id, Seq(Owner), None)
        _ <- organizationUserRepository.upsert(orgUser)
      } yield inserted
    }.seal
}

object OrganizationCreateService {
  final case class Config(organizationLimitUserCanCreate: Option[Int])

  object Config {
    def apply(config: TsConfig): Config = Config(Try(config.getInt("organization.organizationLimitUserCanCreate")).toOption)
  }

  final case class OrganizationCreateRequest(name: OrganizationName, slug: OrganizationSlug)

  sealed trait OrganizationCreateResult
  object OrganizationCreateResult {
    final case class Created(organization: Organization)                          extends OrganizationCreateResult
    final case class UserOrganizationsLimitExceeded(limit: Int, actualCount: Int) extends OrganizationCreateResult
    final case object SlugIsAlreadyTaken                                          extends OrganizationCreateResult
    final case object OperationNotPermitted                                       extends OrganizationCreateResult
  }
}
