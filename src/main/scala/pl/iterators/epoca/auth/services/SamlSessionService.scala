package pl.iterators.epoca.auth.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.auth.repositories.AccountAuthenticationRepository
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.organization.repositories.OrganizationRepository.OrganizationFilters
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.organization.repositories.{OrganizationRepository, OrganizationUserRepository}
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext

class SamlSessionService(
  transactor: PostgresIOTransactor,
  authentication: AccountAuthenticationRepository,
  organizations: OrganizationRepository,
  memberships: OrganizationUserRepository
)(implicit ec: ExecutionContext) {
  def currentOrganization(context: AuthContext): IO[Option[OrganizationSlug]] = SamlSessionScope.binding(context) match {
    case None                                                                                                => IO.pure(None)
    case Some(_) if context.accountAuthentication.exists(_.authenticatedAt.isAfter(java.time.Instant.now())) => IO.pure(None)
    case Some(bound) =>
      transactor
        .executeTransactionally {
          for {
            active  <- authentication.activeHumanForShare(context.id)
            trusted <- if (active) authentication.currentSamlBindingForShare(context.id, bound) else DBIO.successful(false)
            member <- if (trusted) memberships.find(OrganizationUserFilters(organizationId = Some(bound.organizationId), userId = Some(context.id)))
                      else DBIO.successful(None)
            organization <- if (member.exists(_.archivedAt.isEmpty)) organizations.find(OrganizationFilters(id = Some(bound.organizationId)))
                            else DBIO.successful(None)
          } yield organization.filter(_.archivedAt.isEmpty).map(_.slug)
        }
        .handleError(_ => None)
  }
}
