package pl.iterators.epoca.invitation.services

import io.circe.Json
import pl.iterators.epoca.invitation.services.InvitationManagementService._
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.{Organization, OrganizationUser}
import pl.iterators.epoca.organization.repositories.fixtures.{OrganizationFixture, OrganizationUserFixture}
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity}
import pl.iterators.epoca.user.repositories.UserIdentityRepositoryImpl
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.time.Instant
import java.util.UUID

class InvitationManagementServiceSpec extends PgRepositorySpec with OrganizationFixture with OrganizationUserFixture with UserFixture {
  private val repository = new pl.iterators.epoca.invitation.repositories.InvitationOnboardingRepositoryImpl
  private def serviceAt(at: Instant) = new InvitationManagementService(
    null,
    repository,
    new pl.iterators.epoca.invitation.repositories.InvitationRepositoryImpl,
    new pl.iterators.epoca.user.repositories.EmailIdentityRepositoryImpl,
    new InvitationAccessGuard,
    new InvitationSecretService,
    java.time.Clock.fixed(at, java.time.ZoneOffset.UTC)
  )
  private val now     = Instant.parse("2026-10-01T10:00:00Z")
  private val service = serviceAt(now)
  private def invitationId(result: Result) = pl.iterators.epoca.invitation.domain.InvitationDomain
    .InvitationId(UUID.fromString(result.asInstanceOf[Ok].dto.hcursor.get[String]("id").toOption.get))
  private def edit(revision: String = "0"): Json =
    Json.obj("expectedRevision" -> Json.fromString(revision), "firstName" -> Json.fromString("Changed"))
  private def human =
    allGenerators[User].normal.generate.copy(archivedAt = None, accountType = UserAccountType.Human, isSuperAdmin = UserIsSuperAdmin(false))
  private def body(email: String = s"invitation-${UUID.randomUUID()}@example.test"): Json = Json.obj(
    "firstName" -> Json.fromString("Anna"),
    "lastName"  -> Json.fromString("Test"),
    "email"     -> Json.fromString(email),
    "role"      -> Json.fromString("Employee"),
    "locale"    -> Json.fromString("pl")
  )
  private def setup(role: OrganizationUserRole = OrganizationUserRole.Owner, archived: Boolean = false): DBIO[(User, Organization)] = for {
    actor <- withUser(human)
    org   <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
    _ <- withOrganizationUser(
           OrganizationUser(OrganizationUserId.random, org.id, actor.id, Seq(role), Option.when(archived)(OrganizationUserArchivedAt(Instant.now())))
         )
  } yield (actor, org)

  "invitation management" should {
    "allow a current database superadmin to manage invitations without a membership" in withDatabase {
      for {
        ctx     <- setup()
        admin   <- withUser(human.copy(isSuperAdmin = UserIsSuperAdmin(true)))
        created <- service.createAction(admin.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        page      <- service.listAction(admin.id, ctx._2.slug, pl.iterators.epoca.utils.db.PageSizeAndNumber(50, 1), None, false)
        detail    <- service.detailAction(admin.id, ctx._2.slug, id)
        patched   <- service.patchAction(admin.id, ctx._2.slug, id, edit())
        resent    <- serviceAt(now.plusSeconds(60)).resendAction(admin.id, ctx._2.slug, id, UUID.randomUUID(), edit("1"))
        cancelled <- service.cancelAction(admin.id, ctx._2.slug, id, Some(2))
      } yield {
        page.asInstanceOf[Ok].dto.hcursor.get[Long]("count") must beRight(1L)
        page.asInstanceOf[Ok].dto.hcursor.get[Boolean]("featureEnabled") must beRight(false)
        detail must beAnInstanceOf[Ok]
        patched must beAnInstanceOf[Ok]
        resent must beAnInstanceOf[Ok]
        cancelled.asInstanceOf[Ok].dto.hcursor.get[String]("status") must beRight("Cancelled")
      }
    }
    "keep foreign invitation ids tenant scoped for a database superadmin" in withDatabase {
      for {
        ctx     <- setup()
        other   <- setup()
        admin   <- withUser(human.copy(isSuperAdmin = UserIsSuperAdmin(true)))
        created <- service.createAction(admin.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        detail    <- service.detailAction(admin.id, other._2.slug, id)
        filtered  <- service.listAction(admin.id, other._2.slug, pl.iterators.epoca.utils.db.PageSizeAndNumber(50, 1), Some(id), true)
        patched   <- service.patchAction(admin.id, other._2.slug, id, edit())
        resent    <- serviceAt(now.plusSeconds(60)).resendAction(admin.id, other._2.slug, id, UUID.randomUUID(), edit())
        cancelled <- service.cancelAction(admin.id, other._2.slug, id, Some(0))
        row       <- repository.find(id)
      } yield {
        detail mustEqual NotFound
        filtered.asInstanceOf[Ok].dto.hcursor.get[Long]("count") must beRight(0L)
        patched mustEqual NotFound
        resent mustEqual NotFound
        cancelled mustEqual NotFound
        row.get.revision mustEqual 0L
      }
    }
    "serve scoped safe history and detail while feature creation is disabled" in withDatabase {
      for {
        owner   <- setup()
        created <- service.createAction(owner._1.id, owner._2.slug, UUID.randomUUID(), body())
        page    <- service.listAction(owner._1.id, owner._2.slug, pl.iterators.epoca.utils.db.PageSizeAndNumber(50, 1), None, false)
        detail  <- service.detailAction(owner._1.id, owner._2.slug, invitationId(created))
      } yield {
        val dto = page.asInstanceOf[Ok].dto
        dto.hcursor.get[Boolean]("featureEnabled") must beRight(false)
        dto.hcursor.downField("launchDate").focus must beNone
        dto.hcursor.get[Long]("count") must beRight(1L)
        dto.hcursor.downField("results").focus.get.asArray.get.head mustEqual detail.asInstanceOf[Ok].dto
        dto.noSpaces must not(contain("tokenHash"))
        dto.noSpaces must not(contain(created.asInstanceOf[Ok].delivery.get.secret))
      }
    }
    "deny non-Owners and foreign invitation ids on every read shape" in withDatabase {
      for {
        owner    <- setup()
        other    <- setup()
        employee <- setup(OrganizationUserRole.Employee)
        created  <- service.createAction(owner._1.id, owner._2.slug, UUID.randomUUID(), body())
        denied   <- service.listAction(employee._1.id, employee._2.slug, pl.iterators.epoca.utils.db.PageSizeAndNumber(50, 1), None, true)
        foreign  <- service.detailAction(other._1.id, other._2.slug, invitationId(created))
        filtered <-
          service.listAction(other._1.id, other._2.slug, pl.iterators.epoca.utils.db.PageSizeAndNumber(50, 1), Some(invitationId(created)), true)
      } yield {
        denied mustEqual NotFound
        foreign mustEqual NotFound
        filtered.asInstanceOf[Ok].dto.hcursor.get[Long]("count") must beRight(0L)
      }
    }

    "create a pending Employee invitation without creating a user or membership" in withDatabase {
      for {
        ctx           <- setup()
        beforeUsers   <- sql"""SELECT count(*) FROM "user"""".as[Long].head
        beforeMembers <- sql"SELECT count(*) FROM organization_user".as[Long].head
        result        <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        afterUsers    <- sql"""SELECT count(*) FROM "user"""".as[Long].head
        afterMembers  <- sql"SELECT count(*) FROM organization_user".as[Long].head
      } yield {
        result must beAnInstanceOf[Ok]
        afterUsers mustEqual beforeUsers
        afterMembers mustEqual beforeMembers
        val created = result.asInstanceOf[Ok]
        created.dto.hcursor.get[String]("status") must beRight("Pending")
        created.dto.hcursor.get[Vector[String]]("roles") must beRight(Vector("Employee"))
        created.delivery must beSome
      }
    }
    Seq(OrganizationUserRole.Manager, OrganizationUserRole.Employee).foreach { role =>
      s"deny current $role" in withDatabase {
        for {
          ctx    <- setup(role)
          result <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        } yield result mustEqual NotFound
      }
    }
    "deny a revoked Owner even when the caller still holds an old token" in withDatabase {
      for {
        ctx    <- setup(archived = true)
        result <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
      } yield result mustEqual NotFound
    }
    "deny an Owner from a different organization" in withDatabase {
      for {
        ctx    <- setup()
        other  <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        result <- service.createAction(ctx._1.id, other.slug, UUID.randomUUID(), body())
      } yield result mustEqual NotFound
    }
    Seq(IdentityType.MailAddress, IdentityType.Saml).foreach { kind =>
      s"reject an active member reserved through $kind" in withDatabase {
        val email = s"member-${UUID.randomUUID()}@example.test"
        for {
          ctx    <- setup()
          member <- withUser(human)
          _      <- withOrganizationUser(OrganizationUser(OrganizationUserId.random, ctx._2.id, member.id, Seq(OrganizationUserRole.Employee), None))
          _ <- new UserIdentityRepositoryImpl().insert(
                 allGenerators[UserIdentity].normal.generate.copy(userId = member.id, `type` = kind, value = UserIdentityValue("\t" + email + "\n"))
               )
          result <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body(email))
        } yield result mustEqual AlreadyMember
      }
    }
    "replay an identical command without a second send and reject a different payload" in withDatabase {
      val request = body()
      val key     = UUID.randomUUID()
      for {
        ctx      <- setup()
        first    <- service.createAction(ctx._1.id, ctx._2.slug, key, request)
        replay   <- service.createAction(ctx._1.id, ctx._2.slug, key, request)
        conflict <- service.createAction(ctx._1.id, ctx._2.slug, key, request.deepMerge(Json.obj("firstName" -> Json.fromString("Other"))))
      } yield {
        first must beAnInstanceOf[Ok]
        replay mustEqual first.asInstanceOf[Ok].copy(delivery = None)
        conflict mustEqual IdempotencyConflict
      }
    }

    "edit under the whole invitation revision without extending expiry" in withDatabase {
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        patched <- serviceAt(now.plusSeconds(300)).patchAction(ctx._1.id, ctx._2.slug, id, edit())
        stale   <- service.patchAction(ctx._1.id, ctx._2.slug, id, edit())
        row     <- repository.find(id)
      } yield {
        patched must beAnInstanceOf[Ok]
        stale mustEqual RevisionConflict
        row.get.revision mustEqual 1L
        row.get.firstName must beSome("Changed")
        row.get.invitation.expireAt.get.toString mustEqual now.plusSeconds(7 * 86400).toString
      }
    }
    "reject cooperation data in create and edit requests" in withDatabase {
      val cooperation = Json.obj("cooperation" -> Json.obj("profile" -> Json.Null, "terms" -> Json.Null))
      for {
        ctx      <- setup()
        rejected <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body().deepMerge(cooperation))
        created  <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        patched <- service.patchAction(ctx._1.id, ctx._2.slug, id, edit().deepMerge(cooperation))
      } yield {
        rejected mustEqual InvalidRequest("request.cooperation is not allowed")
        patched mustEqual InvalidRequest("request.cooperation is not allowed")
        created.asInstanceOf[Ok].dto.hcursor.downField("cooperation").focus must beNone
      }
    }
    "rate limit resend then rotate generation and extend expiry after 60 seconds" in withDatabase {
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        limited <-
          serviceAt(now.plusSeconds(59))
            .resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), Json.obj("expectedRevision" -> Json.fromString("0")))
        resent <-
          serviceAt(now.plusSeconds(60))
            .resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), Json.obj("expectedRevision" -> Json.fromString("0")))
        row      <- repository.find(id)
        attempts <- repository.attempts(id)
      } yield {
        limited mustEqual RateLimited(1)
        resent must beAnInstanceOf[Ok]
        row.get.generation mustEqual 2L
        row.get.revision mustEqual 1L
        row.get.invitation.expireAt.get.toString mustEqual now.plusSeconds(60 + 7 * 86400).toString
        attempts.size mustEqual 2
        new InvitationSecretService().matches(created.asInstanceOf[Ok].delivery.get.secret, row.get.tokenHash.get.toArray) must beFalse
      }
    }
    "cancel atomically, audit and reject later edits or sends" in withDatabase {
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        stale     <- service.cancelAction(ctx._1.id, ctx._2.slug, id, Some(1))
        cancelled <- service.cancelAction(ctx._1.id, ctx._2.slug, id, Some(0))
        patched   <- service.patchAction(ctx._1.id, ctx._2.slug, id, edit("1"))
        resent    <- serviceAt(now.plusSeconds(60)).resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), edit("1"))
        audit     <- repository.audit(id)
      } yield {
        stale mustEqual RevisionConflict
        cancelled.asInstanceOf[Ok].dto.hcursor.get[String]("status") must beRight("Cancelled")
        patched mustEqual InvitationNotPending
        resent mustEqual InvitationNotPending
        audit.map(_.action).toSet mustEqual Set("Created", "Cancelled")
      }
    }
    "allow editing and resending expired pending invitations while refusing a duplicate create" in withDatabase {
      val request = body()
      val later   = serviceAt(now.plusSeconds(8 * 86400))
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), request)
        id = invitationId(created)
        duplicate <- later.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), request)
        patched   <- later.patchAction(ctx._1.id, ctx._2.slug, id, edit())
        resent    <- later.resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), edit("1"))
      } yield {
        duplicate mustEqual InvitationAlreadyExists
        patched must beAnInstanceOf[Ok]
        resent must beAnInstanceOf[Ok]
        patched.asInstanceOf[Ok].dto.hcursor.get[String]("status") must beRight("Expired")
        resent.asInstanceOf[Ok].dto.hcursor.get[String]("status") must beRight("Pending")
      }
    }

    "keep legacy email and phone invitations at protocol one with no delivery attempt" in withDatabase {
      val requests = Vector(
        OrganizationInvitationCreateService.OrganizationInvitationCreateRequest(
          Some(pl.iterators.epoca.mail.domain.MailDomain.MailAddress(" LEGACY@example.test ")),
          None,
          Some(Seq(OrganizationUserRole.Owner, OrganizationUserRole.Employee)),
          None
        ),
        OrganizationInvitationCreateService.OrganizationInvitationCreateRequest(
          None,
          Some(pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber("+48500600700")),
          None,
          None
        )
      )
      for {
        ctx     <- setup()
        results <- DBIO.sequence(requests.map(service.createLegacyAction(ctx._1.id, ctx._2.slug, _)))
        _ = results.foreach(result => assert(result.isInstanceOf[LegacyCreated]))
        rows     <- DBIO.sequence(results.map(result => repository.find(result.asInstanceOf[LegacyCreated].invitation.id)))
        attempts <- DBIO.sequence(results.map(result => repository.attempts(result.asInstanceOf[LegacyCreated].invitation.id)))
      } yield {
        rows.flatten.map(_.protocolVersion) mustEqual Vector(1, 1)
        rows.flatten.map(_.tokenHash) mustEqual Vector(None, None)
        rows.flatten.map(_.invitation.identityType) mustEqual Vector(IdentityType.MailAddress, IdentityType.Phone)
        attempts.flatten must beEmpty
      }
    }
    "allow database superadmins and deny regular non-Owners for legacy create and delete" in withDatabase {
      val request = OrganizationInvitationCreateService.OrganizationInvitationCreateRequest(
        Some(pl.iterators.epoca.mail.domain.MailDomain.MailAddress("legacy@example.test")),
        None,
        None,
        None
      )
      for {
        ctx     <- setup()
        admin   <- withUser(human.copy(isSuperAdmin = UserIsSuperAdmin(true)))
        regular <- withUser(human)
        denied  <- service.createLegacyAction(regular.id, ctx._2.slug, request)
        created <- service.createLegacyAction(admin.id, ctx._2.slug, request)
        _  = assert(created.isInstanceOf[LegacyCreated])
        id = created.asInstanceOf[LegacyCreated].invitation.id
        forbidden <- service.cancelAction(regular.id, ctx._2.slug, id, None)
        cancelled <- service.cancelAction(admin.id, ctx._2.slug, id, None)
      } yield {
        denied mustEqual NotFound
        forbidden mustEqual NotFound
        cancelled must beAnInstanceOf[Ok]
      }
    }
    "upgrade a completed legacy email only on resend and refuse phone upgrade" in withDatabase {
      val email = OrganizationInvitationCreateService.OrganizationInvitationCreateRequest(
        Some(pl.iterators.epoca.mail.domain.MailDomain.MailAddress("legacy@example.test")),
        None,
        None,
        None
      )
      val phone = email.copy(email = None, phoneNumber = Some(pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber("+48500600700")))
      for {
        ctx          <- setup()
        created      <- service.createLegacyAction(ctx._1.id, ctx._2.slug, email)
        phoneCreated <- service.createLegacyAction(ctx._1.id, ctx._2.slug, phone)
        _       = assert(created.isInstanceOf[LegacyCreated] && phoneCreated.isInstanceOf[LegacyCreated])
        id      = created.asInstanceOf[LegacyCreated].invitation.id
        phoneId = phoneCreated.asInstanceOf[LegacyCreated].invitation.id
        incomplete <- service.resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), edit())
        complete = Json.obj(
                     "expectedRevision" -> Json.fromString("0"),
                     "firstName"        -> Json.fromString("Anna"),
                     "lastName"         -> Json.fromString("Test"),
                     "role"             -> Json.fromString("Employee"),
                     "locale"           -> Json.fromString("pl")
                   )
        sent    <- service.resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), complete)
        refused <- service.resendAction(ctx._1.id, ctx._2.slug, phoneId, UUID.randomUUID(), complete)
        row     <- repository.find(id)
      } yield {
        incomplete must beAnInstanceOf[InvalidRequest]
        sent must beAnInstanceOf[Ok]
        refused must beAnInstanceOf[InvalidRequest]
        row.get.protocolVersion mustEqual 2
      }
    }

    "never expose secrets in DTOs, command results, audit or diagnostic strings" in withDatabase {
      val key = UUID.randomUUID()
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, key, body())
        id = invitationId(created)
        audit   <- repository.audit(id)
        row     <- repository.find(id)
        command <- repository.findCommand(pl.iterators.epoca.invitation.domain.InvitationOnboarding.CommandKey(ctx._1.id, ctx._2.id, "Create", key))
      } yield {
        val value      = created.asInstanceOf[Ok]
        val publicText = value.dto.noSpaces + audit.toString + command.toString + row.toString + value.toString
        publicText must not contain (value.delivery.get.secret)
        Seq("tokenHash", "cookieHash", "password", "csrfHash").foreach(field => assert(!value.dto.noSpaces.contains(field)))
        row.get.invitation.acceptAt must beNone
        row.get.invitation.expireAt must beSome
        value.dto.hcursor.get[String]("revision") must beRight("0")
      }
    }
    "reject changes after acceptance without touching account or other organizations" in withDatabase {
      for {
        ctx        <- setup()
        other      <- withOrganization(allGenerators[Organization].normal.generate.copy(archivedAt = None))
        membership <- withOrganizationUser(OrganizationUser(OrganizationUserId.random, other.id, ctx._1.id, Seq(OrganizationUserRole.Employee), None))
        created    <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        row <- repository.find(id)
        _ <- repository.update(
               row.get.copy(invitation =
                 row.get.invitation.copy(acceptAt = Some(pl.iterators.epoca.invitation.domain.InvitationDomain.InvitationAcceptAt(now)))
               ),
               0,
               1
             )
        patched   <- service.patchAction(ctx._1.id, ctx._2.slug, id, edit("1"))
        resent    <- serviceAt(now.plusSeconds(60)).resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), edit("1"))
        cancelled <- service.cancelAction(ctx._1.id, ctx._2.slug, id, Some(1))
        unchanged <- new pl.iterators.epoca.user.repositories.UserRepositoryImpl().find(ctx._1.id)
        count     <- sql"SELECT count(*) FROM organization_user WHERE id=${membership.id.toString}::uuid AND archived_at IS NULL".as[Long].head
      } yield {
        patched mustEqual InvitationNotPending
        resent mustEqual InvitationNotPending
        cancelled mustEqual InvitationNotPending
        unchanged must beSome(ctx._1)
        count mustEqual 1L
      }
    }
    "recheck all mutation permissions after membership revocation" in withDatabase {
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        _ <-
          sqlu"UPDATE organization_user SET archived_at=now() WHERE organization_id=${ctx._2.id.toString}::uuid AND user_id=${ctx._1.id.toString}::uuid"
        patched   <- service.patchAction(ctx._1.id, ctx._2.slug, id, edit())
        resent    <- serviceAt(now.plusSeconds(60)).resendAction(ctx._1.id, ctx._2.slug, id, UUID.randomUUID(), edit())
        cancelled <- service.cancelAction(ctx._1.id, ctx._2.slug, id, Some(0))
        row       <- repository.find(id)
      } yield {
        patched mustEqual NotFound
        resent mustEqual NotFound
        cancelled mustEqual NotFound
        row.get.revision mustEqual 0L
      }
    }
    "reject malformed input and immutable email changes without a revision write" in withDatabase {
      for {
        ctx     <- setup()
        invalid <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body().deepMerge(Json.obj("role" -> Json.fromString("Admin"))))
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        missing      <- service.patchAction(ctx._1.id, ctx._2.slug, id, Json.obj("firstName" -> Json.fromString("Other")))
        changedEmail <- service.patchAction(ctx._1.id, ctx._2.slug, id, edit().deepMerge(Json.obj("email" -> Json.fromString("other@example.test"))))
        row          <- repository.find(id)
      } yield {
        invalid must beAnInstanceOf[InvalidRequest]
        missing must beAnInstanceOf[InvalidRequest]
        changedEmail must beAnInstanceOf[InvalidRequest]
        row.get.revision mustEqual 0L
      }
    }

    "fail closed for an archived account reservation" in withDatabase {
      val email = s"archived-${UUID.randomUUID()}@example.test"
      for {
        ctx    <- setup()
        member <- withUser(human.copy(archivedAt = Some(UserArchivedAt(now))))
        _ <- new UserIdentityRepositoryImpl().insert(
               allGenerators[UserIdentity].normal.generate
                 .copy(userId = member.id, `type` = IdentityType.Saml, value = UserIdentityValue(email), confirmed = UserIdentityConfirmed(false))
             )
        result <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body(email))
        count  <- repository.count(ctx._2.id)
      } yield {
        result mustEqual IdentityConflict
        count mustEqual 0L
      }
    }
    "treat canonical address and reordered JSON as the same command" in withDatabase {
      val email     = s"canonical-${UUID.randomUUID()}@example.test"
      val key       = UUID.randomUUID()
      val original  = body(email)
      val upper     = email.toCharArray.map(c => if (c >= 'a' && c <= 'z') (c - ('a' - 'A')).toChar else c).mkString
      val reordered = Json.fromFields(original.asObject.get.toVector.reverse).deepMerge(Json.obj("email" -> Json.fromString("\t" + upper + "\n")))
      for {
        ctx    <- setup()
        first  <- service.createAction(ctx._1.id, ctx._2.slug, key, original)
        replay <- service.createAction(ctx._1.id, ctx._2.slug, key, reordered)
      } yield replay mustEqual first.asInstanceOf[Ok].copy(delivery = None)
    }

    "replay a lost resend response without rotating or sending again, even after later cancellation" in withDatabase {
      val key       = UUID.randomUUID()
      val sentAt    = serviceAt(now.plusSeconds(60))
      val request   = edit()
      val reordered = Json.fromFields(request.asObject.get.toVector.reverse)
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        first    <- sentAt.resendAction(ctx._1.id, ctx._2.slug, id, key, request)
        replay   <- sentAt.resendAction(ctx._1.id, ctx._2.slug, id, key, reordered)
        _        <- sentAt.patchAction(ctx._1.id, ctx._2.slug, id, edit("1"))
        _        <- sentAt.cancelAction(ctx._1.id, ctx._2.slug, id, Some(2))
        late     <- sentAt.resendAction(ctx._1.id, ctx._2.slug, id, key, request)
        attempts <- repository.attempts(id)
        row      <- repository.find(id)
      } yield {
        first must beAnInstanceOf[Ok]
        replay mustEqual first.asInstanceOf[Ok].copy(delivery = None)
        late mustEqual first.asInstanceOf[Ok].copy(delivery = None)
        attempts.size mustEqual 2
        row.get.generation mustEqual 2L
      }
    }
    "reject reuse of a resend command with changed payload or another invitation" in withDatabase {
      val key   = UUID.randomUUID()
      val later = serviceAt(now.plusSeconds(60))
      for {
        ctx   <- setup()
        one   <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        two   <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        first <- later.resendAction(ctx._1.id, ctx._2.slug, invitationId(one), key, edit())
        changed <-
          later.resendAction(ctx._1.id, ctx._2.slug, invitationId(one), key, edit().deepMerge(Json.obj("firstName" -> Json.fromString("Another"))))
        other <- later.resendAction(ctx._1.id, ctx._2.slug, invitationId(two), key, edit())
        row   <- repository.find(invitationId(two))
      } yield {
        first must beAnInstanceOf[Ok]
        changed mustEqual IdempotencyConflict
        other mustEqual IdempotencyConflict
        row.get.generation mustEqual 1L
      }
    }
    Seq(true, false).foreach { archived =>
      s"check legacy phone nonmember eligibility under locks (archived=$archived)" in withDatabase {
        val phone   = pl.iterators.epoca.sms.domain.SmsDomain.PhoneNumber("+48600500400")
        val request = OrganizationInvitationCreateService.OrganizationInvitationCreateRequest(None, Some(phone), None, None)
        for {
          ctx       <- setup()
          candidate <- withUser(human.copy(archivedAt = Option.when(archived)(UserArchivedAt(now))))
          _ <-
            new UserIdentityRepositoryImpl().insert(
              allGenerators[UserIdentity].normal.generate.copy(userId = candidate.id, `type` = IdentityType.Phone, value = UserIdentityValue(phone))
            )
          _              <- sqlu"SET CONSTRAINTS ALL IMMEDIATE"
          beforeAudit    <- sql"SELECT count(*) FROM invitation_audit".as[Long].head
          beforeAttempts <- sql"SELECT count(*) FROM invitation_delivery_attempt".as[Long].head
          result         <- service.createLegacyAction(ctx._1.id, ctx._2.slug, request)
          invitations    <- repository.count(ctx._2.id)
          afterAudit     <- sql"SELECT count(*) FROM invitation_audit".as[Long].head
          afterAttempts  <- sql"SELECT count(*) FROM invitation_delivery_attempt".as[Long].head
        } yield {
          if (archived) {
            result mustEqual IdentityConflict
            invitations mustEqual 0L
            afterAudit mustEqual beforeAudit
          } else {
            result must beAnInstanceOf[LegacyCreated]
            invitations mustEqual 1L
            afterAudit mustEqual (beforeAudit + 1L)
          }
          afterAttempts mustEqual beforeAttempts
        }
      }
    }

    "keep current authorization mandatory for resend command replay" in withDatabase {
      val key   = UUID.randomUUID()
      val later = serviceAt(now.plusSeconds(60))
      for {
        ctx     <- setup()
        created <- service.createAction(ctx._1.id, ctx._2.slug, UUID.randomUUID(), body())
        id = invitationId(created)
        first <- later.resendAction(ctx._1.id, ctx._2.slug, id, key, edit())
        _ <-
          sqlu"UPDATE organization_user SET archived_at=now() WHERE organization_id=${ctx._2.id.toString}::uuid AND user_id=${ctx._1.id.toString}::uuid"
        replay <- later.resendAction(ctx._1.id, ctx._2.slug, id, key, edit())
      } yield {
        first must beAnInstanceOf[Ok]
        replay mustEqual NotFound
      }
    }
  }
}
