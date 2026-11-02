package pl.iterators.epoca.organization.services

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.services.OrganizationFindService.OrganizationFindResult
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport
import pl.iterators.epoca.utils.service.SealedMonadServiceIODBIO
import pl.iterators.sealedmonad.syntax._

class OrganizationFindService(transactor: PostgresIOTransactor, organizationRepository: OrganizationRepository)(implicit ioRuntime: IORuntime)
    extends SealedMonadServiceIODBIO[OrganizationFindResult](ioRuntime)
    with LoggingSupport {

  def find(authContext: AuthContext, slug: OrganizationSlug): IO[OrganizationFindResult] = {
    val query: StepDBIO[OrganizationFindResult] = for {
      org <- findOrganization(authContext, slug)
    } yield OrganizationFindResult.Ok(org)
    transactor.execute(query.run)
  }

  private def findOrganization(authContext: AuthContext, slug: OrganizationSlug): StepDBIO[Organization] = {
    val filter = OrganizationFilters(userId = Option.when(!authContext.isSuperAdmin)(authContext.id), slug = Some(slug))
    organizationRepository.find(filter).valueOr(OrganizationFindResult.NotFound)
  }
}

object OrganizationFindService {
  sealed trait OrganizationFindResult
  object OrganizationFindResult {
    final case class Ok(organization: Organization) extends OrganizationFindResult
    final case object NotFound                      extends OrganizationFindResult
  }
}
