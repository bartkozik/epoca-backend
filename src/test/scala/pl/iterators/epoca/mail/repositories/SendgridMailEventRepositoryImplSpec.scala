package pl.iterators.epoca.mail.repositories

import pl.iterators.epoca.mail.domain.MailDomain.{MailHistory, SendgridMailEvent}
import pl.iterators.epoca.mail.repositories.SendgridMailEventRepository.SendgridMailEventFilters
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

class SendgridMailEventRepositoryImplSpec extends PgRepositorySpec {

  private val sut                                          = new SendgridMailEventRepositoryImpl
  private val mailHistoryRepository                        = new MailHistoryRepositoryImpl
  private val gen: Generator[SendgridMailEvent]            = allGenerators[SendgridMailEvent].normal
  private val mailHistoryGenerator: Generator[MailHistory] = allGenerators[MailHistory].normal

  "CRUD" should {
    "works as follow" in withDatabase {
      val i1 = gen.generate.copy(processed = Some(true))
      val i2 = gen.generate.copy(processed = Some(true))
      val i3 = gen.generate.copy(processed = None)
      val i4 = gen.generate.copy(processed = Some(false))

      for {
        _ <- insertMailHistory(Seq(i1, i2, i3, i4))
        _ <- sut.create(Seq(i1, i2, i3, i4))

        l0 <- sut.list(SendgridMailEventFilters())
        l1 <- sut.list(SendgridMailEventFilters(processed = Some(true)))
        l2 <- sut.list(SendgridMailEventFilters(processed = Some(false)))
      } yield {

        l0 should containTheSameElementsAs(Seq(i1, i2, i3, i4))

        l1 should containTheSameElementsAs(Seq(i1, i2))
        l2 should containTheSameElementsAs(Seq(i3, i4))
      }
    }
  }

  private def insertMailHistory(events: Seq[SendgridMailEvent]): DBIO[Unit] =
    DBIO
      .sequence(
        events
          .distinctBy(_.mailUuid)
          .map(event => mailHistoryRepository.create(mailHistoryGenerator.generate.copy(mailUuid = event.mailUuid, email = event.email)))
      )
      .map(_ => ())

}
