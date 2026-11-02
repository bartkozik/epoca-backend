package pl.iterators.epoca.organization.services

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import cats.implicits._
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationDeleteService.OrganizationDeleteResult
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.Sealed
import pl.iterators.sealedmonad.syntax._
import slick.dbio.DBIO

class OrganizationDeleteService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationDeleteResult](ioRuntime)
    with LoggingSupport {

  def delete(authContext: AuthContext, slug: OrganizationSlug): IO[OrganizationDeleteResult] =
    transactor.execute {
      (for {
        _            <- Logger[DBIO].info(s"auth.id=${authContext.id}, organization=$slug").seal
        organization <- findOrganization(slug)
        _            <- checkPermission(authContext, organization)
        _            <- checkIfEmpty(organization)
        softDeleted  <- prepareDeleted(authContext, organization)
        _            <- softDelete(softDeleted)
      } yield OrganizationDeleteResult.Deleted).run
    }

  private def findOrganization(slug: OrganizationSlug): StepDBIO[Organization] =
    organizationRepository
      .find(OrganizationFilters(slug = Some(slug)))
      .valueOrF {
        Logger[DBIO].error(s"organization $slug does not exist").as(OrganizationDeleteResult.OrganizationNotFound)
      }

  private def checkPermission(authContext: AuthContext, organization: Organization): StepDBIO[Unit] =
    Sealed
      .liftF[DBIO, Unit](())
      .ensureF(
        _ => authContext.id == organization.createdBy || authContext.isSuperAdmin,
        Logger[DBIO]
          .warning(s"user ${authContext.id} has no permission")
          .as(OrganizationDeleteResult.OperationNotPermitted)
      )

  private def checkIfEmpty(organization: Organization): StepDBIO[Unit] = {
    def noOtherUserAssigned(ownerId: UserId)(orgUsers: Seq[OrganizationUser]): Boolean =
      orgUsers match {
        case Seq()                                     => true
        case Seq(orgUser) if orgUser.userId == ownerId => true
        case _                                         => false
      }

    organizationUserRepository
      .list(OrganizationUserFilters(organizationId = Some(organization.id)))
      .seal
      .ensureF(
        noOtherUserAssigned(organization.createdBy),
        Logger[DBIO]
          .error(s"organization ${organization.id} has some non-owner users assigned")
          .as(OrganizationDeleteResult.OrganizationHasUsers)
      )
      .map(_ => ())
  }

  private def prepareDeleted(authContext: AuthContext, organization: Organization): StepDBIO[Organization] =
    Clock[DBIO].instantNow.seal.map { now =>
      organization.copy(modifiedAt = OrganizationModifiedAt(now), modifiedBy = authContext.id, archivedAt = Some(OrganizationArchivedAt(now)))
    }

  private def softDelete(organization: Organization): StepDBIO[Organization] =
    (Logger[DBIO].info(s"successfully deleted organization ${organization.id}") *>
      organizationRepository
        .update(organization))
      .valueOr(OrganizationDeleteResult.OrganizationNotFound)
}

object OrganizationDeleteService {
  sealed trait OrganizationDeleteResult
  object OrganizationDeleteResult {
    final case object Deleted               extends OrganizationDeleteResult
    final case object OperationNotPermitted extends OrganizationDeleteResult
    final case object OrganizationHasUsers  extends OrganizationDeleteResult
    final case object OrganizationNotFound  extends OrganizationDeleteResult
  }
}
