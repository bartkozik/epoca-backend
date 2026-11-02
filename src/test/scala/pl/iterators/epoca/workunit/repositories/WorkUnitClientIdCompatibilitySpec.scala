package pl.iterators.epoca.workunit.repositories

import pl.iterators.epoca.client.repositories.fixtures.ClientFixture
import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.project.repositories.fixtures.ProjectFixture
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._
import pl.iterators.epoca.workunit.repositories.fixtures.WorkUnitFixture
import slick.jdbc.SetParameter

import java.sql.Types
import java.util.UUID

class WorkUnitClientIdCompatibilitySpec
    extends PgRepositorySpec
    with OrganizationFixture
    with UserFixture
    with ClientFixture
    with ProjectFixture
    with WorkUnitFixture {

  private implicit val setUuid: SetParameter[UUID] = SetParameter((value, parameters) => parameters.setObject(value, Types.OTHER))

  "work unit client ID compatibility" should {
    "derive client IDs for mixed legacy and new backend write shapes" in withDatabase {
      for {
        organization  <- withOrganization()
        user          <- withUser()
        sourceClient  <- withClient(organization)
        targetClient  <- withClient(organization)
        sourceProject <- withProject(organization, sourceClient)
        targetProject <- withProject(organization, targetClient)
        source        <- withWorkUnit(organization, user, sourceProject)

        newInsertId = UUID.randomUUID()
        _                 <- insertWithoutClientId(newInsertId, source.id.asInstanceOf[UUID], sourceProject.id.asInstanceOf[UUID])
        newInsertClientId <- clientIdFor(newInsertId)

        legacyInsertId = UUID.randomUUID()
        _ <- insertWithClientId(legacyInsertId, source.id.asInstanceOf[UUID], targetProject.id.asInstanceOf[UUID], sourceClient.id.asInstanceOf[UUID])
        legacyRawClientId <- clientIdFor(legacyInsertId)

        _ <- updateFullRowWithClientId(source.id.asInstanceOf[UUID], targetProject.id.asInstanceOf[UUID], sourceClient.id.asInstanceOf[UUID])
        legacyUpdateClientId <- clientIdFor(source.id.asInstanceOf[UUID])

        newUpdateSource   <- withWorkUnit(organization, user, sourceProject)
        _                 <- updateProjectOnly(newUpdateSource.id.asInstanceOf[UUID], targetProject.id.asInstanceOf[UUID])
        newUpdateClientId <- clientIdFor(newUpdateSource.id.asInstanceOf[UUID])

        mismatchedClientIds <- mismatchedClientIdCount
      } yield {
        newInsertClientId shouldEqual sourceClient.id.toString
        legacyRawClientId shouldEqual targetClient.id.toString
        legacyUpdateClientId shouldEqual targetClient.id.toString
        newUpdateClientId shouldEqual targetClient.id.toString
        mismatchedClientIds shouldEqual 0L
      }
    }
  }

  private def insertWithoutClientId(
    id: UUID,
    sourceId: UUID,
    projectId: UUID
  ): DBIO[Int] =
    sqlu"""
      INSERT INTO work_unit (
        id, organization_id, user_id, project_id, date, duration, description, task_id, state, created_at, modified_at, archived_at
      )
      SELECT
        $id, organization_id, user_id, $projectId, date, duration, description, task_id, state, created_at, modified_at, archived_at
      FROM work_unit
      WHERE id = $sourceId
    """

  private def insertWithClientId(
    id: UUID,
    sourceId: UUID,
    projectId: UUID,
    clientId: UUID
  ): DBIO[Int] =
    sqlu"""
      INSERT INTO work_unit (
        id, organization_id, user_id, project_id, client_id, date, duration, description, task_id, state, created_at, modified_at, archived_at
      )
      SELECT
        $id, organization_id, user_id, $projectId, $clientId, date, duration, description, task_id, state, created_at, modified_at, archived_at
      FROM work_unit
      WHERE id = $sourceId
    """

  private def updateFullRowWithClientId(
    id: UUID,
    projectId: UUID,
    clientId: UUID
  ): DBIO[Int] =
    sqlu"""
      UPDATE work_unit
      SET
        organization_id = organization_id,
        user_id = user_id,
        project_id = $projectId,
        client_id = $clientId,
        date = date,
        duration = duration,
        description = description,
        task_id = task_id,
        state = state,
        created_at = created_at,
        modified_at = modified_at,
        archived_at = archived_at
      WHERE id = $id
    """

  private def updateProjectOnly(id: UUID, projectId: UUID): DBIO[Int] =
    sqlu"""UPDATE work_unit SET project_id = $projectId WHERE id = $id"""

  private def clientIdFor(workUnitId: UUID): DBIO[String] =
    sql"""SELECT client_id::text FROM work_unit WHERE id = $workUnitId""".as[String].head

  private def mismatchedClientIdCount: DBIO[Long] =
    sql"""
      SELECT COUNT(*)
      FROM work_unit wu
      JOIN project p ON p.id = wu.project_id
      WHERE wu.client_id IS DISTINCT FROM p.client_id
    """.as[Long].head
}
