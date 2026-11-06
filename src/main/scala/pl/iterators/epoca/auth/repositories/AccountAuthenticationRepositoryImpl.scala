package pl.iterators.epoca.auth.repositories

import pl.iterators.epoca.auth.domain.AccountAuthentication.BoundSaml
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._

import scala.concurrent.ExecutionContext

class AccountAuthenticationRepositoryImpl(implicit ec: ExecutionContext) extends AccountAuthenticationRepository {
  override def activeHumanForShare(userId: UserId): DBIO[Boolean] =
    sql"""SELECT 1 FROM "user" WHERE id = ${userId.toString}::uuid
      AND account_type = 'Human' AND archived_at IS NULL FOR SHARE""".as[Int].map(_.nonEmpty)

  override def currentSamlBindingForShare(userId: UserId, evidence: BoundSaml): DBIO[Boolean] =
    for {
      organization <- sql"""SELECT 1 FROM organization WHERE id = ${evidence.organizationId.toString}::uuid
        AND archived_at IS NULL FOR SHARE""".as[Int]
      config <- if (organization.nonEmpty) sql"""SELECT 1 FROM saml_config
        WHERE organization_id = ${evidence.organizationId.toString}::uuid AND trust_epoch = ${evidence.trustEpoch}
          AND idp_entity_id = ${evidence.issuer} AND is_enabled FOR SHARE""".as[Int] else DBIO.successful(Vector.empty[Int])
      binding <- if (config.nonEmpty) sql"""SELECT 1 FROM saml_identity_binding
        WHERE organization_id = ${evidence.organizationId.toString}::uuid AND trust_epoch = ${evidence.trustEpoch}
          AND issuer = ${evidence.issuer} AND subject = ${evidence.subject} AND user_id = ${userId.toString}::uuid
        FOR SHARE""".as[Int] else DBIO.successful(Vector.empty[Int])
    } yield binding.nonEmpty
}
