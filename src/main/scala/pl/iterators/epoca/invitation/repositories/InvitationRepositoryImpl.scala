package pl.iterators.epoca.invitation.repositories

import pl.iterators.epoca.invitation.domain.Invitation
import pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationId
import pl.iterators.epoca.invitation.repositories.InvitationJdbc._
import pl.iterators.epoca.invitation.repositories.InvitationRepository.InvitationFilters
import pl.iterators.epoca.invitation.repositories.tables.InvitationTable
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationId
import pl.iterators.epoca.organization.repositories.tables.OrganizationTable
import pl.iterators.epoca.saml.repositories.tables.SamlConfigTable
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserAccountType, UserId, UserIdentityValue}
import pl.iterators.epoca.user.repositories.tables.{UserIdentityTable, UserTable}
import pl.iterators.epoca.utils.db.BaseRepository.driver.api._
import pl.iterators.epoca.utils.db.{EmailCanonicalization, FilteringRepository, IdRepository}
import slick.ast.BaseTypedType
import slick.lifted.{ProvenShape, TableQuery}

import java.time.Instant
import scala.concurrent.ExecutionContext

class InvitationRepositoryImpl(implicit e: ExecutionContext)
    extends IdRepository[Invitation, InvitationId, InvitationTable]
    with FilteringRepository[Invitation, InvitationTable, InvitationFilters]
    with InvitationRepository {

  override protected def ec: ExecutionContext                         = e
  override protected val table: TableQuery[InvitationTable]           = TableQuery[InvitationTable]
  override protected def getId: Invitation => InvitationId            = _.id
  override protected def idBaseTypedType: BaseTypedType[InvitationId] = implicitly[BaseTypedType[InvitationId]]

  private lazy val identities    = TableQuery[UserIdentityTable]
  private lazy val organizations = TableQuery[OrganizationTable]
  private lazy val samlConfigs   = TableQuery[SamlConfigTable]
  private class Binding(tag: Tag) extends Table[(OrganizationId, UserId, Long, String, String, String)](tag, "saml_identity_binding") {
    def organizationId: Rep[OrganizationId]                                     = column[OrganizationId]("organization_id")
    def userId: Rep[UserId]                                                     = column[UserId]("user_id")
    def epoch: Rep[Long]                                                        = column[Long]("trust_epoch")
    def issuer: Rep[String]                                                     = column[String]("issuer")
    def subject: Rep[String]                                                    = column[String]("subject")
    def email: Rep[String]                                                      = column[String]("canonical_email")
    def * : ProvenShape[(OrganizationId, UserId, Long, String, String, String)] = (organizationId, userId, epoch, issuer, subject, email)
  }
  private lazy val bindings                         = TableQuery[Binding]
  private lazy val userTable: TableQuery[UserTable] = TableQuery[UserTable]

  override def lockPhone(value: UserIdentityValue): DBIO[Unit] = SimpleDBIO { ctx =>
    statement(ctx.connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))") { p =>
      bind(p, s"phone:$value"); val result = p.executeQuery(); result.close()
    }
  }
  override def phoneCandidates(value: UserIdentityValue): DBIO[Vector[UserId]] = SimpleDBIO { ctx =>
    statement(ctx.connection, "SELECT DISTINCT user_id FROM user_identity WHERE type='Phone' AND value=? ORDER BY user_id") { p =>
      bind(p, value); rows(p)(r => UserId(uuid(r, "user_id")))
    }
  }
  override def pendingPhone(organizationId: OrganizationId, value: UserIdentityValue): DBIO[Boolean] = SimpleDBIO { ctx =>
    statement(
      ctx.connection,
      "SELECT id FROM invitation WHERE organization_id=? AND identity_type='Phone' AND identity_value=? AND accept_at IS NULL AND reject_at IS NULL AND archived_at IS NULL"
    ) { p =>
      bind(p, organizationId, value); rows(p)(r => uuid(r, "id")).nonEmpty
    }
  }

  override def count(filter: InvitationFilters): DBIO[Int] =
    prepareFilterQuery(table, filter.copy(pageSizeAndNumber = None)).length.result

  override protected def prepareFilterQuery(tableQuery: Query[InvitationTable, Invitation, Seq], params: InvitationFilters)
    : Query[InvitationTable, Invitation, Seq] = {
    val query = tableQuery
      .filterOpt(params.id)(_.id === _)
      .filterOpt(params.protocolVersion)(_.protocolVersion === _)
      .filterOpt(params.organizationId)(_.organizationId === _)
      .filterOpt(params.acceptableOn) { (row, instant) =>
        (row.expireAt.isEmpty || row.expireAt.map(_.asInstanceOf[Rep[Instant]]) > instant) &&
        row.acceptAt.isEmpty &&
        row.rejectAt.isEmpty
      }
      .filterOpt(params.userId) { (row, userId) =>
        val human = userTable
          .filter(u => u.id === userId && u.accountType === LiteralColumn(UserAccountType.Human: UserAccountType) && u.archivedAt.isEmpty)
          .exists
        val ownContact = identities
          .filter(i =>
            i.userId === userId && i.confirmed && (
              (row.identityType === LiteralColumn(IdentityType.MailAddress: IdentityType) && i.`type` === LiteralColumn(
                IdentityType.MailAddress: IdentityType
              ) && EmailCanonicalization.column(i.value.asColumnOf[String]) === EmailCanonicalization.column(row.identityValue.asColumnOf[String])) ||
                (row.identityType === LiteralColumn(IdentityType.Phone: IdentityType) && i.`type` === LiteralColumn(
                  IdentityType.Phone: IdentityType
                ) && i.value === row.identityValue)
            )
          )
          .exists
        val bound = params.boundSaml.fold[Rep[Boolean]](LiteralColumn(false)) { proof =>
          bindings
            .filter(b =>
              b.userId === userId && b.organizationId === row.organizationId && b.organizationId === proof.organizationId && b.epoch === proof.trustEpoch && b.issuer === proof.issuer && b.subject === proof.subject && b.email === EmailCanonicalization
                .column(row.identityValue.asColumnOf[String]) &&
                samlConfigs
                  .filter(c => c.id === b.organizationId && c.trustEpoch === b.epoch && c.idpEntityId.asColumnOf[String] === b.issuer && c.isEnabled)
                  .exists &&
                organizations.filter(o => o.id === b.organizationId && o.archivedAt.isEmpty).exists
            )
            .exists && row.identityType === LiteralColumn(IdentityType.MailAddress: IdentityType)
        }
        human && (ownContact || bound)
      }
      .filterIf(!params.includeArchived)(_.notDeletedNow)

    params.pageSizeAndNumber.fold(query) { page =>
      query.drop(page.offset).take(page.size)
    }
  }
}
