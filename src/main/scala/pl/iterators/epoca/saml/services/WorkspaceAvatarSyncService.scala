package pl.iterators.epoca.saml.services

import cats.effect.IO
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.user.domain.UserDomain.{UserAvatar, UserId}
import pl.iterators.epoca.user.domain.{User, UserAvatarSource, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserAvatarSourceRepository
import pl.iterators.epoca.user.services.AvatarSyncService
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.utils.db.PostgresIOTransactor
import pl.iterators.epoca.utils.log.LoggingSupport

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** Best effort, detached after successful login. Limits are per application process; database CAS protects concurrent processes. */
class WorkspaceAvatarSyncService(
  provider: WorkspaceDirectoryPhotoProvider,
  avatars: AvatarSyncService,
  sources: UserAvatarSourceRepository,
  transactor: PostgresIOTransactor
)(implicit ec: ExecutionContext)
    extends LoggingSupport {
  private def admission = avatars.admission

  def schedule(user: UserWithIdentities, proof: WorkspacePhotoProof): IO[Unit] = IO
    .defer {
      if (!provider.configured(proof)) IO.unit
      else
        IO.uncancelable { _ =>
          IO(admission.acquire(user.id, System.nanoTime())).flatMap {
            case false => IO.unit
            case true  =>
              // Capture provenance before credentials/photo IO so older completions cannot overwrite a newer result.
              val job = IO.defer {
                val deadlineNanos = System.nanoTime() + 30.seconds.toNanos
                transactor.executeBoundedTransactionally(deadlineNanos)(sources.find(user.id)).flatMap { expected =>
                  val manual = expected
                    .fold(user.avatar.isDefined)(s => s.source == UserAvatarSource.Source.Manual || !user.avatar.contains(UserAvatar(s.avatarUrl)))
                  if (manual) IO.unit
                  else
                    provider.fetch(proof).flatMap {
                      case None => IO.unit
                      case Some(photo) =>
                        avatars
                          .syncImage(
                            user.into[User].transform,
                            photo.revision,
                            photo.bytes,
                            photo.contentType,
                            WorkspaceAvatarSyncService.stillAuthorized(user.id, proof, Some(deadlineNanos)),
                            expected,
                            Some(deadlineNanos)
                          )
                          .flatMap {
                            case _: AvatarSyncService.AvatarSyncResult.Failed   => Logger[IO].warning("Workspace photo storage failed")
                            case _: AvatarSyncService.AvatarSyncResult.Rejected => Logger[IO].warning("Workspace photo storage rejected")
                            case _                                              => IO.unit
                          }
                    }
                }
              }
              job
                .timeoutTo(30.seconds, Logger[IO].warning("Workspace photo job deadline exceeded"))
                .handleErrorWith(_ => Logger[IO].warning("Workspace photo job failed"))
                .guarantee(IO(admission.release()))
                .start
                .void
                .handleErrorWith(_ => IO(admission.release()) >> Logger[IO].warning("Workspace photo dispatch failed"))
          }
        }
    }
    .handleErrorWith(_ => Logger[IO].warning("Workspace photo dispatch failed"))
}

object WorkspaceAvatarSyncService {

  /** Called in AvatarSyncService's transaction after its user FOR UPDATE lock. Same user -> organization lock order as SAML exchange. */
  private[services] def stillAuthorized(
    userId: UserId,
    proof: WorkspacePhotoProof,
    deadlineNanos: Option[Long] = None
  )(implicit ec: ExecutionContext
  ): DBIO[Boolean] = {
    def check = deadlineNanos.fold[DBIO[Unit]](DBIO.successful(()))(PostgresIOTransactor.requireBefore)
    for {
      _ <- check
      organization <-
        sql"SELECT id::text FROM organization WHERE id=${proof.organizationId.toString}::uuid AND archived_at IS NULL FOR UPDATE".as[String]
      _ <- check
      configuration <- sql"""SELECT 1 FROM saml_config
      WHERE organization_id=${proof.organizationId.toString}::uuid AND is_enabled
        AND trust_epoch=${proof.trustEpoch} AND idp_entity_id=${proof.issuer} FOR SHARE""".as[Int]
      _ <- check
      binding <- sql"""SELECT 1 FROM saml_identity_binding
      WHERE organization_id=${proof.organizationId.toString}::uuid AND user_id=${userId.toString}::uuid
        AND trust_epoch=${proof.trustEpoch} AND issuer=${proof.issuer} AND subject=${proof.subject}
        AND canonical_email=${proof.canonicalEmail} FOR SHARE""".as[Int]
      _ <- check
      membership <- sql"""SELECT 1 FROM organization_user
      WHERE organization_id=${proof.organizationId.toString}::uuid AND user_id=${userId.toString}::uuid
        AND archived_at IS NULL FOR SHARE""".as[Int]
      _ <- check
    } yield organization.nonEmpty && configuration.nonEmpty && binding.nonEmpty && membership.nonEmpty
  }
}
