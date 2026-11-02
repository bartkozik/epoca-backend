package pl.iterators.epoca.saml.repositories

import io.circe.Json
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.saml.domain.{SamlBindingKey, SamlIdentityBinding}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import slick.lifted.ProvenShape

import java.time.Instant
import scala.concurrent.ExecutionContext

final class SamlIdentityBindingRepositoryImpl(implicit ec: ExecutionContext) extends SamlIdentityBindingRepository {
  private class BindingTable(tag: Tag) extends Table[SamlIdentityBinding](tag, "saml_identity_binding") {
    def organizationId: Rep[OrganizationId] = column[OrganizationId]("organization_id")
    def trustEpoch: Rep[Long]               = column[Long]("trust_epoch")
    def issuer: Rep[String]                 = column[String]("issuer")
    def subject: Rep[String]                = column[String]("subject")
    def userId: Rep[UserId]                 = column[UserId]("user_id")
    def canonicalEmail: Rep[String]         = column[String]("canonical_email")
    def verifiedAt: Rep[Instant]            = column[Instant]("verified_at")
    def proof: Rep[Json]                    = column[Json]("proof")
    override def * : ProvenShape[SamlIdentityBinding] =
      (organizationId, trustEpoch, issuer, subject, userId, canonicalEmail, verifiedAt, proof) <> (row =>
        SamlIdentityBinding(SamlBindingKey(row._1, row._2, row._3, row._4), row._5, row._6, row._7, row._8),
      (binding: SamlIdentityBinding) =>
        Some(
          (
            binding.key.organizationId,
            binding.key.trustEpoch,
            binding.key.issuer,
            binding.key.subject,
            binding.userId,
            binding.canonicalEmail,
            binding.verifiedAt,
            binding.proof
          )
        ))
  }
  private val bindings = TableQuery[BindingTable]
  private def matching(key: SamlBindingKey) = bindings.filter(b =>
    b.organizationId === key.organizationId && b.trustEpoch === key.trustEpoch && b.issuer === key.issuer && b.subject === key.subject
  )
  override def find(key: SamlBindingKey): DBIO[Option[SamlIdentityBinding]] = matching(key).result.headOption
  override def lock(key: SamlBindingKey): DBIO[Option[SamlIdentityBinding]] = matching(key).forUpdate.result.headOption
  override def insert(binding: SamlIdentityBinding): DBIO[Unit]             = (bindings += binding).map(_ => ())
}
