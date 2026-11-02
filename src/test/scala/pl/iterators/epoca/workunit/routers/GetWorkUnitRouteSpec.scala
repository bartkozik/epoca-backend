package pl.iterators.epoca.workunit.routers

import io.circe.Encoder
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.UriLengthTestSupport.{FormerMaxUriLength, SupportedMaxUriLength, withQuery}
import pl.iterators.epoca.utils.db.{PageSizeAndNumber, PaginatedResult}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitDuration, WorkUnitEntryType, WorkUnitId, WorkUnitState}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters
import pl.iterators.epoca.workunit.services.WorkUnitDtoService.WorkUnitDto
import pl.iterators.epoca.workunit.services.WorkUnitListService.{WorkUnitListData, WorkUnitListResult}
import pl.iterators.epoca.workunit.services.{WorkUnitListService, WorkUnitOverrideDto}

import java.time.{Duration, LocalDate}
import java.util.UUID

class GetWorkUnitRouteSpec extends RouteDocSpec {
  private implicit lazy val exactWorkUnitDtoEncoder: Encoder.AsObject[WorkUnitDto] = WorkUnitDto.encoder

  private val documentedId                         = UUID.fromString("55555555-5555-4555-8555-555555555555")
  private val documentedProjectId                  = UUID.fromString("66666666-6666-4666-8666-666666666666")
  private val documentedUserId                     = UUID.fromString("77777777-7777-4777-8777-777777777777")
  private val documentedClientId                   = UUID.fromString("88888888-8888-4888-8888-888888888888")
  private val shouldReturnDraftIdsDefault: Boolean = false

  override val routeRepresentation: RouteRepresentation[Unit, WorkUnitListData] =
    RouteRepresentation[Unit, WorkUnitListData](
      "List work units. Requesting users' work units require admin/owner/manager access.\n" +
        "Parameters id, dateFrom, dateTo, projectId - list, userId - list, clientId - list, " +
        "state - list, includeArchived - bool, shouldReturnDraftIds - bool. Project/client filters use effective values. " +
        "pageSize - pagination, pageNumber - pagination",
      "GET",
      "/organizations/{organizationSlug}/work-units",
      List(
        RouteParameterRepresentation[UUID]("id", false, documentedId, _.toString),
        RouteParameterRepresentation[LocalDate]("dateFrom", false, LocalDate.parse("2026-08-01"), _.toString),
        RouteParameterRepresentation[LocalDate]("dateTo", false, LocalDate.parse("2026-08-06"), _.toString),
        RouteParameterRepresentation[UUID]("projectId", false, documentedProjectId, _.toString),
        RouteParameterRepresentation[UUID]("userId", false, documentedUserId, _.toString),
        RouteParameterRepresentation[UUID]("clientId", false, documentedClientId, _.toString),
        RouteParameterRepresentation[WorkUnitState]("state", false, WorkUnitState.Draft, _.toString),
        RouteParameterRepresentation[Boolean]("includeArchived", false, false, _.toString),
        RouteParameterRepresentation[Boolean]("shouldReturnDraftIds", shouldReturnDraftIdsDefault, false, _.toString),
        RouteParameterRepresentation[Int]("pageSize", false, 25, _.toString),
        RouteParameterRepresentation[Int]("pageNumber", false, 1, _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext           = allGenerators[AuthContext].normal.generate
  val orgSlug: OrganizationSlug   = OrganizationSlug("pm-contract-org")
  val routePath: String           = routeRepresentation.path.replace("{organizationSlug}", orgSlug)
  val routePathWithParams: String = routeRepresentation.routePathWithAllParameters.replace("{organizationSlug}", orgSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val workUnitListService: WorkUnitListService = vmock[WorkUnitListService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound if service returns NoAccessToOrganization" in new LoggedTestCase {
      val filter = WorkUnitFilters()
      when(workUnitListService.listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter))
        .thenReturn(io(WorkUnitListResult.NoAccessToOrganization))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitListService).listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter)
    }

    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      val filter = WorkUnitFilters()
      when(workUnitListService.listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter))
        .thenReturn(io(WorkUnitListResult.OrganizationNotFound))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitListService).listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter)
    }

    "return Forbidden if service returns OverrideForbidden" in new LoggedTestCase {
      val filter = WorkUnitFilters()
      when(workUnitListService.listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter))
        .thenReturn(io(WorkUnitListResult.OverrideForbidden))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitListService).listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter)
    }

    "return Ok with service data otherwise" in new LoggedTestCase {
      val generatedResponse = routeRepresentation.response.random.value
      val noteDto = allGenerators[WorkUnitDto].normal.generate
        .copy(duration = WorkUnitDuration(Duration.ZERO), entryType = WorkUnitEntryType.Note, `override` = WorkUnitOverrideDto.empty)
      val classifiedDto =
        allGenerators[WorkUnitDto].normal.generate.copy(`override` = WorkUnitOverrideDto.empty.copy(active = true))
      val responseObject = generatedResponse.copy(workUnits = PaginatedResult(2, Seq(noteDto, classifiedDto)))
      val filter         = WorkUnitFilters()
      when(workUnitListService.listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter))
        .thenReturn(io(WorkUnitListResult.Ok(responseObject)))

      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        val noteJson = responseAs[Json].hcursor.downField("workUnits").downField("results").downArray
        noteJson.get[String]("entryType") shouldEqual Right("Note")
        noteJson.get[String]("duration") shouldEqual Right("PT0S")
        noteJson.focus.flatMap(_.asObject).get.contains("pmClassification") should beFalse

        val classifiedJson = noteJson.right
        classifiedJson.focus.flatMap(_.asObject).get.contains("pmClassification") should beFalse
        classifiedJson
          .downField("override")
          .get[Boolean]("active") shouldEqual Right(classifiedDto.`override`.active)
      }

      verify(workUnitListService).listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter)
    }

    "return Ok with service data when all optional params provided" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val filter = WorkUnitFilters(
        id = routeRepresentation.getParamValue[WorkUnitId]("id"),
        dateFrom = routeRepresentation.getParamValue[WorkUnitDate]("dateFrom"),
        dateTo = routeRepresentation.getParamValue[WorkUnitDate]("dateTo"),
        projectIds = routeRepresentation.getParamValue[ProjectId]("projectId").map(Seq(_)),
        userIds = routeRepresentation.getParamValue[UserId]("userId").map(Seq(_)),
        states = routeRepresentation.getParamValue[WorkUnitState]("state").map(Seq(_)),
        clientIds = routeRepresentation.getParamValue[ClientId]("clientId").map(Seq(_)),
        includeArchived = routeRepresentation.getParamValue[Boolean]("includeArchived").getOrElse(false),
        pageSizeAndNumber =
          PageSizeAndNumber.fromOptions(routeRepresentation.getParamValue[Int]("pageSize"), routeRepresentation.getParamValue[Int]("pageNumber"))
      )
      private val shouldCheckDrafts = routeRepresentation.getParamValue[Boolean]("shouldReturnDraftIds").getOrElse(false)

      when(workUnitListService.listWithOrder(auth, orgSlug, shouldCheckDrafts, filter))
        .thenReturn(io(WorkUnitListResult.Ok(responseObject.value)))

      TestRequest(routePathWithParams, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(workUnitListService).listWithOrder(auth, orgSlug, shouldCheckDrafts, filter)
    }

    "preserve every repeated filter from a URI above the former limit" in new LoggedTestCase {
      val responseObject = routeRepresentation.response.random
      val projectIds     = Seq.fill(20)(ProjectId(UUID.randomUUID()))
      val userIds        = Seq.fill(20)(UserId(UUID.randomUUID()))
      val clientIds      = Seq.fill(20)(ClientId(UUID.randomUUID()))
      val states         = WorkUnitState.values
      val longRoutePath = withQuery(
        routePath,
        "projectId" -> projectIds.map(_.toString),
        "userId"    -> userIds.map(_.toString),
        "state"     -> states.map(_.toString),
        "clientId"  -> clientIds.map(_.toString)
      )
      val longRouteUri = Uri(path = Uri.Path(routePath), queryString = Some(longRoutePath.drop(routePath.length + 1)))
      val filter = WorkUnitFilters(
        projectIds = Some(projectIds.reverse),
        userIds = Some(userIds.reverse),
        states = Some(states.reverse),
        clientIds = Some(clientIds.reverse)
      )

      (longRoutePath.length > FormerMaxUriLength) shouldEqual true
      (longRoutePath.length <= SupportedMaxUriLength) shouldEqual true
      when(workUnitListService.listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter))
        .thenReturn(io(WorkUnitListResult.Ok(responseObject.value)))

      HttpRequest(uri = longRouteUri) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(workUnitListService).listWithOrder(auth, orgSlug, shouldReturnDraftIdsDefault, filter)
    }
  }
}
