package pl.iterators.epoca.utils

import org.scalacheck.Gen.{alphaChar, alphaNumChar, listOfN, nonEmptyListOf}
import org.scalacheck.{Arbitrary, Gen}
import pl.iterators.epoca.auth.domain.{AuthContext, AuthContextAuthedBy}
import pl.iterators.epoca.client.domain.ClientDomain.{ClientColor, ClientName}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthClientPublicId
import pl.iterators.epoca.organization.domain.OrganizationDomain._
import pl.iterators.epoca.organization.domain.OrganizationUser
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectName
import pl.iterators.epoca.saml.domain.SamlDomain.SamlRequestId
import pl.iterators.epoca.stats.domain.StatsDomain.StatsYearMonth
import pl.iterators.epoca.testsupport.generators.GeneratorVariants
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDuration}
import pl.iterators.kebs.jsonschema.KebsJsonSchemaPredefs
import pl.iterators.kebs.scalacheck.KebsArbitrarySupport

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID
import scala.util.Random

trait Generators extends GeneratorVariants with KebsJsonSchemaPredefs with KebsArbitrarySupport with JsonArbitraryPredef {

  implicit lazy val arbitrarySamlRequest: Arbitrary[pl.iterators.epoca.saml.domain.SamlRequest] = Arbitrary {
    import pl.iterators.epoca.saml.domain.SamlDomain._
    for {
      id      <- Arbitrary.arbitrary[SamlRequestId]
      token   <- Arbitrary.arbitrary[Option[SamlOneTimeToken]]
      user    <- Arbitrary.arbitrary[Option[UserId]]
      used    <- Arbitrary.arbitrary[SamlRequestUsed]
      created <- Arbitrary.arbitrary[SamlRequestCreatedAt]
    } yield pl.iterators.epoca.saml.domain.SamlRequest(id, token, user, used, created)
  }

  implicit val arbitraryWorkUnitOverrideState: Arbitrary[pl.iterators.epoca.workunit.domain.WorkUnitOverrideState] =
    Arbitrary(Gen.const(pl.iterators.epoca.workunit.domain.WorkUnitOverrideState()))

  implicit val arbitraryWorkUnitTaskOverride: Arbitrary[pl.iterators.epoca.workunit.domain.WorkUnitTaskOverride] =
    Arbitrary(Gen.const(pl.iterators.epoca.workunit.domain.WorkUnitTaskOverride("Inherit")))

  implicit lazy val arbitraryWorkUnit: Arbitrary[pl.iterators.epoca.workunit.domain.WorkUnit] = Arbitrary {
    import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
    for {
      id           <- Arbitrary.arbitrary[WorkUnitId]
      user         <- Arbitrary.arbitrary[UserId]
      organization <- Arbitrary.arbitrary[OrganizationId]
      project      <- Arbitrary.arbitrary[pl.iterators.epoca.project.domain.ProjectDomain.ProjectId]
      date         <- Arbitrary.arbitrary[WorkUnitDate]
      duration     <- Arbitrary.arbitrary[WorkUnitDuration]
      entryType    <- Arbitrary.arbitrary[WorkUnitEntryType]
      description  <- Arbitrary.arbitrary[WorkUnitDescription]
      task         <- Arbitrary.arbitrary[Option[WorkUnitTaskId]]
      state        <- Arbitrary.arbitrary[WorkUnitState]
      created      <- Arbitrary.arbitrary[WorkUnitCreatedAt]
      modified     <- Arbitrary.arbitrary[WorkUnitModifiedAt]
      archived     <- Arbitrary.arbitrary[Option[WorkUnitArchivedAt]]
    } yield pl.iterators.epoca.workunit.domain.WorkUnit(
      id,
      user,
      organization,
      project,
      date,
      duration,
      entryType,
      description,
      task,
      state,
      created,
      modified,
      archived
    )
  }

  implicit override val arbInstant: Arbitrary[Instant] =
    Arbitrary {
      Gen.chooseNum(Int.MinValue, Int.MaxValue).map { value =>
        Instant.ofEpochMilli(System.currentTimeMillis()).plusMillis(value.toLong)
      }
    }

  implicit val arbMailAddress: Arbitrary[MailAddress] =
    Arbitrary {
      Gen.chooseNum(10, 12).flatMap { v1 =>
        Gen.chooseNum(10, 12).flatMap { v2 =>
          Gen.chooseNum(10, 12).map { v3 =>
            val user   = Random.alphanumeric.take(v1).mkString
            val domain = Random.alphanumeric.take(v2).mkString
            val ext    = Random.alphanumeric.take(v3).mkString
            MailAddress(s"$user@$domain.$ext")
          }
        }
      }
    }

  implicit val arbitraryUserRole: Arbitrary[OrganizationUserRole] = Arbitrary {
    Gen.oneOf(OrganizationUserRole.values)
  }

  implicit val arbitraryOrganizationUser: Arbitrary[OrganizationUser] =
    Arbitrary {
      for {
        id             <- Arbitrary.arbitrary[UUID].map(OrganizationUserId.apply)
        organizationId <- Arbitrary.arbitrary[UUID].map(OrganizationId.apply)
        userId         <- Arbitrary.arbitrary[UUID].map(UserId.apply)
        role           <- Arbitrary.arbitrary[OrganizationUserRole]
        archivedAt     <- Arbitrary.arbitrary[Option[Instant]].map(_.map(OrganizationUserArchivedAt.apply))
      } yield OrganizationUser(id, organizationId, userId, Seq(role), archivedAt)
    }

  implicit val arbitraryUser: Arbitrary[User] =
    Arbitrary {
      for {
        userId     <- Arbitrary.arbitrary[UUID].map(UserId.apply)
        firstName  <- Arbitrary.arbitrary[UserFirstName]
        lastName   <- Arbitrary.arbitrary[UserLastName]
        createdAt  <- Arbitrary.arbitrary[Instant].map(UserCreatedAt.apply)
        modifiedAt <- Arbitrary.arbitrary[Instant].map(UserModifiedAt.apply)
        archivedAt <- Arbitrary.arbitrary[Option[Instant]].map(_.map(UserArchivedAt.apply))
      } yield User(userId, firstName, lastName, None, None, UserIsSuperAdmin(false), createdAt, modifiedAt, archivedAt)
    }

  implicit val arbitraryAuthContext: Arbitrary[AuthContext] =
    Arbitrary {
      for {
        user <- Arbitrary.arbitrary[User]
        now  <- Arbitrary.arbitrary[Instant]
      } yield AuthContext(
        id = user.id,
        isSuperAdmin = UserIsSuperAdmin(false),
        identities = Nil,
        authedBy = Set(AuthContextAuthedBy(IdentityType.MailAddress, None)),
        roles = Nil,
        lastAuthedAt = now,
        fresh = true
      )
    }

  implicit val arbitraryYearMonth: Arbitrary[StatsYearMonth] =
    Arbitrary {
      Gen
        .choose(LocalDate.of(1970, 1, 1).toEpochDay, LocalDate.of(2100, 12, 31).toEpochDay)
        .map(LocalDate.ofEpochDay)
        .map(date => StatsYearMonth.from(date))
    }

  implicit val arbitraryDuration: Arbitrary[Duration] =
    Arbitrary {
      Gen.chooseNum(0L, 999999L).map { value =>
        Duration.ofSeconds(value)
      }
    }

  implicit val arbitraryUserBirthDate: Arbitrary[WorkUnitDate] = Arbitrary(
    Gen.choose(LocalDate.of(1970, 1, 1).toEpochDay, LocalDate.of(2100, 12, 31).toEpochDay).map(LocalDate.ofEpochDay).map(WorkUnitDate.apply)
  )

  implicit val arbUserFirstName: Arbitrary[UserFirstName] =
    Arbitrary {
      nonEmptyListOf(alphaChar).map((chars: List[Char]) => UserFirstName(chars.mkString))
    }

  implicit val arbUserLastName: Arbitrary[UserLastName] =
    Arbitrary {
      nonEmptyListOf(alphaChar).map((chars: List[Char]) => UserLastName(chars.mkString))
    }

  implicit val arbSamlRequestId: Arbitrary[SamlRequestId] =
    Arbitrary {
      nonEmptyListOf(alphaChar).map((chars: List[Char]) => SamlRequestId(chars.mkString))
    }

  implicit val arbUserColor: Arbitrary[UserColor] =
    Arbitrary {
      Gen.chooseNum(0x0, 0xffffff).map(num => UserColor(s"#${(num | 0x1000000).toHexString.drop(1)}"))
    }

  implicit val arbClientName: Arbitrary[ClientName] =
    Arbitrary {
      nonEmptyListOf(alphaChar).map((chars: List[Char]) => ClientName(chars.mkString))
    }

  implicit val arbProjectName: Arbitrary[ProjectName] =
    Arbitrary {
      nonEmptyListOf(alphaChar).map((chars: List[Char]) => ProjectName(chars.mkString))
    }

  implicit val arbOAuthClientPublicId: Arbitrary[OAuthClientPublicId] =
    Arbitrary {
      listOfN(22, alphaNumChar).map(chars => OAuthClientPublicId(s"epoca_oauth_client_v1_${chars.mkString}"))
    }

  implicit val arbOrganizationSlug: Arbitrary[OrganizationSlug] =
    Arbitrary {
      Gen.uuid.map(id => OrganizationSlug(id.toString))
    }

  implicit val arbOrganizationName: Arbitrary[OrganizationName] =
    Arbitrary {
      nonEmptyListOf(alphaChar).map((chars: List[Char]) => OrganizationName(chars.mkString))
    }

  implicit val arbClientColor: Arbitrary[ClientColor] =
    Arbitrary {
      Gen.chooseNum(0x0, 0xffffff).map(num => ClientColor(s"#${(num | 0x1000000).toHexString.drop(1)}"))
    }

  implicit val arbWorkUnitDuration: Arbitrary[WorkUnitDuration] =
    Arbitrary {
      Arbitrary.arbitrary[Duration].map(WorkUnitDuration.apply)
    }

}

object Generators {}
