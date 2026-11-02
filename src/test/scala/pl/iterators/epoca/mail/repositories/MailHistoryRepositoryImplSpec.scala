package pl.iterators.epoca.mail.repositories

import pl.iterators.epoca.mail.domain.MailDomain.MailHistory
import pl.iterators.epoca.mail.repositories.MailHistoryRepository.MailHistoryFilters
import pl.iterators.epoca.utils.PgRepositorySpec

import java.time.Instant

class MailHistoryRepositoryImplSpec extends PgRepositorySpec {

  private val sut = new MailHistoryRepositoryImpl
  private val gen = allGenerators[MailHistory].normal

  "CRUD" should {
    "works as follow" in withDatabase {
      for {
        i1 <- sut.create(gen.generate)
        i2 <- sut.create(gen.generate)
        i3 <- sut.create(gen.generate)

        l0 <- sut.list(MailHistoryFilters())
        l1 <- sut.list(MailHistoryFilters(mailUuid = Some(i1.mailUuid)))

        instant = Instant.ofEpochMilli(System.currentTimeMillis())
        _ <- sut.update(i1.copy(clicked = Some(instant)))

        l1u <- sut.list(MailHistoryFilters(mailUuid = Some(i1.mailUuid)))
      } yield {
        l0 should containTheSameElementsAs(Seq(i1, i2, i3))

        l1 should containTheSameElementsAs(Seq(i1))

        l1u should containTheSameElementsAs(Seq(i1.copy(clicked = Some(instant))))
      }
    }
  }

  "API success update" should {
    "preserve webhook timestamps written before the API result" in withDatabase {
      val createdAt    = Instant.parse("2026-07-23T08:00:00Z")
      val delivered    = Instant.parse("2026-07-23T08:00:01Z")
      val opened       = Instant.parse("2026-07-23T08:00:02Z")
      val clicked      = Instant.parse("2026-07-23T08:00:03Z")
      val apiSuccess   = Instant.parse("2026-07-23T08:00:04Z")
      val pending      = gen.generate.copy(created = createdAt, apiSuccess = None, delivered = None, clicked = None, opened = None)
      val synchronized = pending.copy(delivered = Some(delivered), clicked = Some(clicked), opened = Some(opened))

      for {
        _           <- sut.create(pending)
        _           <- sut.updateWebhookTimestamps(pending.mailUuid, Some(delivered), Some(opened), Some(clicked))
        updatedRows <- sut.updateApiSuccess(pending.mailUuid, Some(apiSuccess))
        stored      <- sut.find(MailHistoryFilters(mailUuid = Some(pending.mailUuid)))
      } yield {
        updatedRows shouldEqual 1
        stored shouldEqual Some(synchronized.copy(apiSuccess = Some(apiSuccess)))
      }
    }

    "clear only API success when given None" in withDatabase {
      val history = gen.generate.copy(
        created = Instant.parse("2026-07-23T09:00:00Z"),
        apiSuccess = Some(Instant.parse("2026-07-23T09:00:01Z")),
        delivered = Some(Instant.parse("2026-07-23T09:00:02Z")),
        clicked = Some(Instant.parse("2026-07-23T09:00:03Z")),
        opened = Some(Instant.parse("2026-07-23T09:00:04Z"))
      )

      for {
        _           <- sut.create(history)
        updatedRows <- sut.updateApiSuccess(history.mailUuid, None)
        stored      <- sut.find(MailHistoryFilters(mailUuid = Some(history.mailUuid)))
      } yield {
        updatedRows shouldEqual 1
        stored shouldEqual Some(history.copy(apiSuccess = None))
      }
    }
  }

  "Webhook timestamp update" should {
    "preserve API success and fill delivered and opened from a click" in withDatabase {
      val apiSuccess = Instant.parse("2026-07-23T10:00:01Z")
      val clicked    = Instant.parse("2026-07-23T10:00:02Z")
      val pending =
        gen.generate.copy(created = Instant.parse("2026-07-23T10:00:00Z"), apiSuccess = None, delivered = None, opened = None, clicked = None)
      val expected = pending.copy(apiSuccess = Some(apiSuccess), delivered = Some(clicked), opened = Some(clicked), clicked = Some(clicked))

      for {
        _           <- sut.create(pending)
        _           <- sut.updateApiSuccess(pending.mailUuid, Some(apiSuccess))
        updatedRows <- sut.updateWebhookTimestamps(pending.mailUuid, None, None, Some(clicked))
        stored      <- sut.find(MailHistoryFilters(mailUuid = Some(pending.mailUuid)))
      } yield {
        updatedRows shouldEqual 1
        stored shouldEqual Some(expected)
      }
    }

    "keep existing timestamps and unrelated fields exact" in withDatabase {
      val existing = gen.generate.copy(
        created = Instant.parse("2026-07-23T11:00:00Z"),
        apiSuccess = Some(Instant.parse("2026-07-23T11:00:01Z")),
        delivered = Some(Instant.parse("2026-07-23T11:00:02Z")),
        opened = Some(Instant.parse("2026-07-23T11:00:03Z")),
        clicked = Some(Instant.parse("2026-07-23T11:00:04Z"))
      )

      for {
        _ <- sut.create(existing)
        updatedRows <- sut.updateWebhookTimestamps(
                         existing.mailUuid,
                         Some(Instant.parse("2026-07-23T11:01:02Z")),
                         Some(Instant.parse("2026-07-23T11:01:03Z")),
                         Some(Instant.parse("2026-07-23T11:01:04Z"))
                       )
        stored <- sut.find(MailHistoryFilters(mailUuid = Some(existing.mailUuid)))
      } yield {
        updatedRows shouldEqual 1
        stored shouldEqual Some(existing)
      }
    }
  }

}
