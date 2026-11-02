package pl.iterators.epoca.organization.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationSlug}
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.organization.services.OrganizationUserMembershipService.OrganizationUserMembershipResult
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class OrganizationUserMembershipService(
  transactor: PostgresIOTransactor,
  organizationRepository: OrganizationRepository,
  organizationUserRepository: OrganizationUserRepository
)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationUserMembershipResult](ioRuntime)
    with LoggingSupport {

  def membershipBySlug(slug: OrganizationSlug, userId: UserId): IO[OrganizationUserMembershipResult] =
    (for {
      organization <- findOrganization(slug)
      result       <- findMembership(organization.id, userId)
    } yield result).run

  def membershipById(id: OrganizationId, userId: UserId): IO[OrganizationUserMembershipResult] =
    (for {
      result <- findMembership(id, userId)
    } yield result).run

  private def findOrganization(slug: OrganizationSlug): StepIO[Organization] =
    transactor
      .execute {
        organizationRepository
          .find(OrganizationFilters(slug = Some(slug)))
      }
      .valueOrF {
        Logger[IO].error(s"organization $slug does not exist").as(OrganizationUserMembershipResult.None)
      }

  private def findMembership(organizationId: OrganizationId, userId: UserId): StepIO[OrganizationUserMembershipResult] =
    transactor
      .execute(organizationUserRepository.find(OrganizationUserFilters(organizationId = Some(organizationId), userId = Some(userId))))
      .map {
        case Some(organizationUser) =>
          if (organizationUser.isOrgAdmin) OrganizationUserMembershipResult.Admin
          else if (organizationUser.isManager) OrganizationUserMembershipResult.Manager
          else OrganizationUserMembershipResult.Member
        case None => OrganizationUserMembershipResult.None
      }
      .seal

}

object OrganizationUserMembershipService {
  sealed trait OrganizationUserMembershipResult

  object OrganizationUserMembershipResult {
    final case object Admin   extends OrganizationUserMembershipResult
    final case object Manager extends OrganizationUserMembershipResult
    final case object Member  extends OrganizationUserMembershipResult
    final case object None    extends OrganizationUserMembershipResult
  }
}
