package pl.iterators.epoca.workunit.services

import io.circe.syntax._
import io.scalaland.chimney.dsl._
import pl.iterators.epoca.client.domain.Client
import pl.iterators.epoca.client.repositories.ClientRepository
import pl.iterators.epoca.client.repositories.ClientRepository.ClientFilters
import pl.iterators.epoca.project.domain.Project
import pl.iterators.epoca.project.repositories.ProjectRepository
import pl.iterators.epoca.project.repositories.ProjectRepository.ProjectFilters
import pl.iterators.epoca.testsupport.generators.AllGenerators
import pl.iterators.epoca.user.domain.User
import pl.iterators.epoca.user.repositories.UserRepository
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.epoca.workunit.domain.WorkUnitDomain._
import pl.iterators.epoca.workunit.domain.{WorkUnit, WorkUnitOrder, WorkUnitOverrideState}
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.{WorkUnitDto, WorkUnitProjectClientDto}

import java.time.{Duration, Instant}

class WorkUnitDtoServiceSpec extends ServiceSpec {
  private object jsonProtocol extends JsonProtocol {
    implicit lazy val exactWorkUnitDtoEncoder: io.circe.Encoder.AsObject[WorkUnitDto] = WorkUnitDto.encoder
  }
  import jsonProtocol._

  lazy val workUnitGenerator: AllGenerators[WorkUnit]           = allGenerators[WorkUnit]
  lazy val workUnitOrderGenerator: AllGenerators[WorkUnitOrder] = allGenerators[WorkUnitOrder]
  lazy val projectGenerator: AllGenerators[Project]             = allGenerators[Project]
  lazy val clientGenerator: AllGenerators[Client]               = allGenerators[Client]
  lazy val userGenerator: AllGenerators[User]                   = allGenerators[User]

  trait TestCase extends BaseScope {
    val clientRepository: ClientRepository   = vmock[ClientRepository]
    val userRepository: UserRepository       = vmock[UserRepository]
    val projectRepository: ProjectRepository = vmock[ProjectRepository]

    val sut: WorkUnitDtoService = wire[WorkUnitDtoService]

    private val client1  = clientGenerator.normal.generate
    private val client2  = clientGenerator.normal.generate
    private val project1 = projectGenerator.normal.generate.copy(clientId = client1.id)
    private val project2 = projectGenerator.normal.generate.copy(clientId = client2.id)
    private val project3 = projectGenerator.normal.generate.copy(clientId = client2.id)

    private val clientProject1 = WorkUnitProjectClientDto.from(client1, project1)
    private val clientProject2 = WorkUnitProjectClientDto.from(client2, project2)
    private val clientProject3 = WorkUnitProjectClientDto.from(client2, project3)

    private val user = userGenerator.normal.generate
    private val workUnit1 = workUnitGenerator.normal.generate.copy(
      userId = user.id,
      projectId = project1.id,
      entryType = WorkUnitEntryType.Worklog,
      duration = WorkUnitDuration(Duration.ofMinutes(30))
    )
    private val workUnit2 = workUnitGenerator.normal.generate.copy(
      userId = user.id,
      projectId = project1.id,
      entryType = WorkUnitEntryType.Note,
      duration = WorkUnitDuration(Duration.ZERO)
    )
    private val workUnit3 =
      workUnitGenerator.normal.generate.copy(userId = user.id, projectId = project2.id, date = workUnit1.date)
    private val workUnit4 =
      workUnitGenerator.normal.generate.copy(userId = user.id, projectId = project2.id, date = workUnit2.date)
    private val workUnit5 = workUnitGenerator.normal.generate.copy(
      userId = user.id,
      projectId = project3.id,
      date = workUnit2.date,
      createdAt = WorkUnitCreatedAt(workUnit4.createdAt.plusSeconds(400L))
    )
    private val workUnit6 =
      workUnitGenerator.normal.generate.copy(userId = user.id, projectId = project3.id, date = workUnit2.date)

    private val workUnitDto1 = workUnit1
      .into[WorkUnitDto]
      .withFieldConst(_.projectClient, Some(clientProject1))
      .withFieldConst(_.userFullName, Some(user.fullName))
      .withFieldComputed(
        _.effectiveProjectClient,
        unit => Map(project1.id -> clientProject1, project2.id -> clientProject2, project3.id -> clientProject3).get(unit.effectiveProjectId)
      )
      .withFieldComputed(_.effectiveTaskId, _.effectiveTaskId)
      .withFieldComputed(_.`override`, unit => WorkUnitOverrideDto.from(unit, Map(user.id -> user.fullName)))
      .transform
    private val workUnitDto2 = workUnit2
      .into[WorkUnitDto]
      .withFieldConst(_.projectClient, Some(clientProject1))
      .withFieldConst(_.userFullName, Some(user.fullName))
      .withFieldComputed(
        _.effectiveProjectClient,
        unit => Map(project1.id -> clientProject1, project2.id -> clientProject2, project3.id -> clientProject3).get(unit.effectiveProjectId)
      )
      .withFieldComputed(_.effectiveTaskId, _.effectiveTaskId)
      .withFieldComputed(_.`override`, unit => WorkUnitOverrideDto.from(unit, Map(user.id -> user.fullName)))
      .transform
    private val workUnitDto3 = workUnit3
      .into[WorkUnitDto]
      .withFieldConst(_.projectClient, Some(clientProject2))
      .withFieldConst(_.userFullName, Some(user.fullName))
      .withFieldComputed(
        _.effectiveProjectClient,
        unit => Map(project1.id -> clientProject1, project2.id -> clientProject2, project3.id -> clientProject3).get(unit.effectiveProjectId)
      )
      .withFieldComputed(_.effectiveTaskId, _.effectiveTaskId)
      .withFieldComputed(_.`override`, unit => WorkUnitOverrideDto.from(unit, Map(user.id -> user.fullName)))
      .transform
    private val workUnitDto4 = workUnit4
      .into[WorkUnitDto]
      .withFieldConst(_.projectClient, Some(clientProject2))
      .withFieldConst(_.userFullName, Some(user.fullName))
      .withFieldComputed(
        _.effectiveProjectClient,
        unit => Map(project1.id -> clientProject1, project2.id -> clientProject2, project3.id -> clientProject3).get(unit.effectiveProjectId)
      )
      .withFieldComputed(_.effectiveTaskId, _.effectiveTaskId)
      .withFieldComputed(_.`override`, unit => WorkUnitOverrideDto.from(unit, Map(user.id -> user.fullName)))
      .transform
    private val workUnitDto5 = workUnit5
      .into[WorkUnitDto]
      .withFieldConst(_.projectClient, Some(clientProject3))
      .withFieldConst(_.userFullName, Some(user.fullName))
      .withFieldComputed(
        _.effectiveProjectClient,
        unit => Map(project1.id -> clientProject1, project2.id -> clientProject2, project3.id -> clientProject3).get(unit.effectiveProjectId)
      )
      .withFieldComputed(_.effectiveTaskId, _.effectiveTaskId)
      .withFieldComputed(_.`override`, unit => WorkUnitOverrideDto.from(unit, Map(user.id -> user.fullName)))
      .transform
    private val workUnitDto6 = workUnit6
      .into[WorkUnitDto]
      .withFieldConst(_.projectClient, Some(clientProject3))
      .withFieldConst(_.userFullName, Some(user.fullName))
      .withFieldComputed(
        _.effectiveProjectClient,
        unit => Map(project1.id -> clientProject1, project2.id -> clientProject2, project3.id -> clientProject3).get(unit.effectiveProjectId)
      )
      .withFieldComputed(_.effectiveTaskId, _.effectiveTaskId)
      .withFieldComputed(_.`override`, unit => WorkUnitOverrideDto.from(unit, Map(user.id -> user.fullName)))
      .transform

    private val order1 =
      workUnitOrderGenerator.normal.generate.copy(
        userId = user.id,
        date = workUnit1.date,
        workUnitIds = Seq(workUnit1.id, workUnit3.id, workUnit5.id)
      )
    private val order2 =
      workUnitOrderGenerator.normal.generate.copy(userId = user.id, date = workUnit2.date, workUnitIds = Seq(workUnit2.id, workUnit6.id))

    val orderMap: Map[WorkUnitDate, Seq[WorkUnitId]] =
      Seq(workUnit1.date -> Seq(workUnit1.id, workUnit3.id), workUnit2.date -> Seq(workUnit2.id, workUnit6.id, workUnit4.id, workUnit5.id)).toMap

    val projects: Seq[Project]         = Seq(project1, project2, project3)
    val clients: Seq[Client]           = Seq(client1, client2)
    val users: Seq[User]               = Seq(user)
    val workUnits: Seq[WorkUnit]       = Seq(workUnit1, workUnit2, workUnit3, workUnit4, workUnit5, workUnit6)
    val workUnitDtos: Seq[WorkUnitDto] = Seq(workUnitDto1, workUnitDto2, workUnitDto3, workUnitDto4, workUnitDto5, workUnitDto6)
    val orders: Seq[WorkUnitOrder]     = Seq(order1, order2)

    val projectFilters: ProjectFilters = ProjectFilters(ids = Some(projects.map(_.id)), includeArchived = true)
    val clientFilters: ClientFilters   = ClientFilters(ids = Some(projects.map(_.clientId).distinct), includeArchived = true)
    val userFilters: UserFilters       = UserFilters(ids = Some(Seq(user.id)), includeArchived = true)
  }

  "prepareDtos" should {
    "copy every work-unit value and attach independently supplied lookups" in new TestCase {
      val workUnit      = workUnits.head
      val project       = projects.find(_.id == workUnit.projectId).get
      val client        = clients.find(_.id == project.clientId).get
      val projectClient = WorkUnitProjectClientDto.from(client, project)
      val user          = users.find(_.id == workUnit.userId).get

      WorkUnitDto.from(workUnit, Map(project.id -> projectClient), Map(user.id -> user.fullName)) shouldEqual WorkUnitDto(
        id = workUnit.id,
        userId = workUnit.userId,
        userFullName = Some(user.fullName),
        date = workUnit.date,
        duration = workUnit.duration,
        entryType = workUnit.entryType,
        description = workUnit.description,
        taskId = workUnit.taskId,
        state = workUnit.state,
        createdAt = workUnit.createdAt,
        modifiedAt = workUnit.modifiedAt,
        projectClient = Some(projectClient),
        effectiveProjectClient = Some(projectClient),
        effectiveTaskId = workUnit.taskId,
        revision = workUnit.revision,
        `override` = WorkUnitOverrideDto.from(workUnit, Map(user.id -> user.fullName))
      )
    }

    "preserve the ordinary 12-argument WorkUnitDto constructor with conservative capabilities" in new TestCase {
      val source = workUnitDtos.head
      val legacy = WorkUnitDto(
        source.id,
        source.userId,
        source.userFullName,
        source.date,
        source.duration,
        source.entryType,
        source.description,
        source.taskId,
        source.state,
        source.createdAt,
        source.modifiedAt,
        source.projectClient
      )

      legacy shouldEqual source
      legacy.`override`.permissions shouldEqual WorkUnitOverridePermissionsDto()
    }

    "prepares dtos for provided work units" in new TestCase {
      when(userRepository.list(userFilters)).thenReturn(users.asDBIO)
      when(clientRepository.list(clientFilters)).thenReturn(clients.asDBIO)
      when(projectRepository.list(projectFilters)).thenReturn(projects.asDBIO)

      val result = sut.prepareDtos(workUnits)
      transactor(result).asserting(_ should containTheSameElementsAs(workUnitDtos))

      verify(projectRepository).list(projectFilters)
      verify(clientRepository).list(clientFilters)
      verify(userRepository).list(userFilters)
    }

    "batch current override metadata without changing source fields or inferring history from version" in new TestCase {
      val target    = projects.last
      val source    = workUnits.head
      val changedAt = Instant.parse("2026-09-17T12:00:00Z")
      val values = Seq(
        source.copy(overrideState =
          WorkUnitOverrideState(projectId = Some(target.id), version = 7, lastChangedBy = Some(users.head.id), lastChangedAt = Some(changedAt))
        ),
        source.copy(overrideState = WorkUnitOverrideState(taskIdOverrideSet = true, taskId = Some(WorkUnitTaskId("CORRECTED")))),
        source.copy(overrideState = WorkUnitOverrideState(taskIdOverrideSet = true)),
        source.copy(overrideState = WorkUnitOverrideState(version = 9, hasHistory = true)),
        source
      )
      val expectedProjectFilters = ProjectFilters(ids = Some(Seq(source.projectId, target.id)), includeArchived = true)
      val selectedProjects       = projects.filter(p => Set(source.projectId, target.id).contains(p.id))
      val expectedClientFilters  = ClientFilters(ids = Some(selectedProjects.map(_.clientId).distinct), includeArchived = true)
      when(projectRepository.list(expectedProjectFilters)).thenReturn(selectedProjects.asDBIO)
      when(clientRepository.list(expectedClientFilters)).thenReturn(clients.asDBIO)
      when(userRepository.list(userFilters)).thenReturn(users.asDBIO)
      transactor(sut.prepareDtos(values)).asserting { dtos =>
        dtos.foreach { dto =>
          dto.projectClient shouldEqual workUnitDtos.head.projectClient
          dto.taskId shouldEqual source.taskId
          dto.`override`.permissions shouldEqual WorkUnitOverridePermissionsDto()
        }
        dtos.head.effectiveProjectClient.map(_.projectId) shouldEqual Some(target.id)
        dtos.head.`override`.version shouldEqual 7L
        dtos.head.`override`.hasHistory should beFalse
        dtos.head.`override`.lastChangedBy.map(_.displayName) shouldEqual Some(users.head.fullName)
        dtos(1).effectiveTaskId shouldEqual Some(WorkUnitTaskId("CORRECTED"))
        dtos(2).effectiveTaskId should beNone
        dtos(3).`override`.active should beFalse
        dtos(3).`override`.hasHistory should beTrue
        dtos(4).effectiveTaskId shouldEqual source.taskId
      }
      verify(projectRepository).list(expectedProjectFilters)
      verify(clientRepository).list(expectedClientFilters)
      verify(userRepository).list(userFilters)
    }

    "always encode explicit null effective task and metadata without a separate PM entity" in new TestCase {
      val dto  = workUnitDtos.head.copy(effectiveTaskId = None)
      val json = dto.asJson
      json.asObject.get.contains("pmClassification") should beFalse
      json.hcursor.downField("effectiveTaskId").focus shouldEqual Some(_root_.io.circe.Json.Null)
      json.hcursor.downField("override").get[Boolean]("active") shouldEqual Right(false)
    }

    "preserve Worklog and Note entry types with neutral Note duration" in new TestCase {
      when(userRepository.list(userFilters)).thenReturn(users.asDBIO)
      when(clientRepository.list(clientFilters)).thenReturn(clients.asDBIO)
      when(projectRepository.list(projectFilters)).thenReturn(projects.asDBIO)

      transactor(sut.prepareDtos(workUnits)).asserting { dtos =>
        dtos.find(_.id == workUnits.head.id).map(_.entryType) shouldEqual Some(WorkUnitEntryType.Worklog)
        dtos.find(_.id == workUnits(1).id).map(dto => dto.entryType -> dto.duration) shouldEqual
          Some(WorkUnitEntryType.Note -> WorkUnitDuration(Duration.ZERO))
      }

      verify(projectRepository).list(projectFilters)
      verify(clientRepository).list(clientFilters)
      verify(userRepository).list(userFilters)
    }
  }
  "prepareOrder" should {
    "based on provided dtos and orders prepare ordering map by date" in new TestCase {
      sut.prepareOrder(workUnitDtos, orders).toSeq should containTheSameElementsAs(orderMap.toSeq)
    }
  }

}
