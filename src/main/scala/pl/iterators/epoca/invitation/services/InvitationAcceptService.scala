package pl.iterators.epoca.invitation.services

import cats.effect.IO
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.invitation.domain.InvitationDomain._
import pl.iterators.epoca.invitation.domain.InvitationOnboarding.Audit
import pl.iterators.epoca.invitation.domain.InvitationPolicy
import pl.iterators.epoca.invitation.repositories.{InvitationOnboardingRepository, InvitationRepository}
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.repositories.EmailIdentityRepository.Resolution
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{EmailIdentityRepository, UserRepository}
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor

import java.time.Clock
import java.util.UUID
import scala.concurrent.ExecutionContext

class InvitationAcceptService(
  transactor: PostgresIOTransactor,
  invitationOnboardingRepository: InvitationOnboardingRepository,
  invitationRepository: InvitationRepository,
  emailIdentityRepository: EmailIdentityRepository,
  userRepository: UserRepository,
  invitationActivationActions: InvitationActivationActions,
  clock: Clock) {
  import InvitationAcceptService._
  private implicit val ec: ExecutionContext = ExecutionContext.global

  def accept(authContext: AuthContext, request: InvitationAcceptRequest): IO[InvitationAcceptResult] =
    transactor
      .executeTransactionally(acceptAction(authContext, request))
      .handleErrorWith(error =>
        InvitationActivationService.recover(error) match {
          case Some(InvitationActivationService.SignInRequired) => IO.pure(InvitationAcceptResult.SignInRequired)
          case Some(InvitationActivationService.AlreadyMember)  => IO.pure(InvitationAcceptResult.AlreadyMember)
          case Some(_)                                          => IO.pure(InvitationAcceptResult.InvitationNotFound)
          case None                                             => IO.raiseError(error)
        }
      )

  private[invitation] def acceptAction(auth: AuthContext, request: InvitationAcceptRequest): DBIO[InvitationAcceptResult] =
    invitationOnboardingRepository.find(request.invitationId).flatMap {
      case Some(hint) if hint.protocolVersion == 1 =>
        val reservation = hint.invitation.identityType match {
          case IdentityType.MailAddress =>
            emailIdentityRepository
              .lock(hint.invitation.identityValue)
              .flatMap(_ => emailIdentityRepository.resolve(hint.invitation.identityValue))
              .flatMap {
                case Resolution.Empty           => DBIO.successful(Vector.empty[UserId])
                case Resolution.Single(user, _) => DBIO.successful(Vector(user.id))
                case Resolution.Ambiguous       => InvitationActivationService.fail(InvitationActivationService.IdentityConflict)
              }
          case IdentityType.Phone =>
            invitationRepository
              .lockPhone(hint.invitation.identityValue)
              .flatMap(_ => invitationRepository.phoneCandidates(hint.invitation.identityValue))
          case _ => InvitationActivationService.fail(InvitationActivationService.InvalidInvitation)
        }
        for {
          candidates <- reservation
          ids = (candidates :+ auth.id).distinct.sortBy(_.toString)
          _ <- DBIO.sequence(ids.map(id => sql"""SELECT id::text FROM "user" WHERE id=${id.toString}::uuid FOR UPDATE""".as[String]))
          _ <- DBIO.sequence(
                 (InvitationActivationService.proofOrganizations(Some(auth)) :+ hint.invitation.organizationId).distinct
                   .sortBy(_.toString)
                   .map(id => sql"SELECT id::text FROM organization WHERE id=${id.toString}::uuid FOR UPDATE".as[String])
               )
          org <- sql"SELECT id::text FROM organization WHERE id=${hint.invitation.organizationId.toString}::uuid AND archived_at IS NULL".as[String]
          _ <-
            DBIO.sequence(
              ids.map(id =>
                sql"SELECT id::text FROM organization_user WHERE organization_id=${hint.invitation.organizationId.toString}::uuid AND user_id=${id.toString}::uuid ORDER BY id FOR UPDATE"
                  .as[String]
              )
            )
          locked <- invitationOnboardingRepository.lockInvitation(request.invitationId)
          user   <- userRepository.find(UserFilters(id = Some(auth.id)))
          result <- locked match {
                      case Some(record)
                          if record.protocolVersion == 1 && record.invitation.organizationId == hint.invitation.organizationId && record.invitation.identityValue == hint.invitation.identityValue && org.nonEmpty && user
                            .exists(_.accountType == UserAccountType.Human) && InvitationPolicy.isAcceptable(
                            clock.instant(),
                            record.invitation.expireAt,
                            record.invitation.acceptAt,
                            record.invitation.rejectAt,
                            record.invitation.archivedAt
                          ) =>
                        invitationActivationActions.recipientMatches(record, auth).flatMap {
                          case false => DBIO.successful(InvitationAcceptResult.InvitationNotFound)
                          case true if request.accept =>
                            invitationActivationActions
                              .authenticateRecipient(record, auth)
                              .andThen(invitationActivationActions.join(record, auth.id))
                              .map(_ => InvitationAcceptResult.Done)
                          case true =>
                            val now = clock.instant()
                            for {
                              updated <- invitationOnboardingRepository.update(
                                           record.copy(invitation =
                                             record.invitation.copy(
                                               rejectAt = Some(InvitationRejectAt(now)),
                                               modifiedBy = auth.id,
                                               modifiedAt = InvitationModifiedAt(now)
                                             )
                                           ),
                                           record.revision,
                                           record.generation
                                         )
                              _ <- if (updated) DBIO.successful(())
                                   else InvitationActivationService.fail(InvitationActivationService.RevisionConflict)
                              _ <- invitationOnboardingRepository.appendAudit(
                                     Audit(InvitationAuditId(UUID.randomUUID()), record.invitation.id, Some(auth.id), "Rejected", now, None, None)
                                   )
                            } yield InvitationAcceptResult.Done
                        }
                      case _ => DBIO.successful(InvitationAcceptResult.InvitationNotFound)
                    }
        } yield result
      case _ => DBIO.successful(InvitationAcceptResult.InvitationNotFound)
    }
}
object InvitationAcceptService {
  final case class InvitationAcceptRequest(invitationId: InvitationId, accept: Boolean)
  sealed trait InvitationAcceptResult
  object InvitationAcceptResult {
    case object Done               extends InvitationAcceptResult
    case object InvitationNotFound extends InvitationAcceptResult
    case object SignInRequired     extends InvitationAcceptResult
    case object AlreadyMember      extends InvitationAcceptResult
  }
}
