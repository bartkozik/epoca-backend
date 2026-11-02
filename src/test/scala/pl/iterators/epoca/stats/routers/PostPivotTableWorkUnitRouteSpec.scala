package pl.iterators.epoca.stats.routers

import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes, Uri}
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.client.domain.ClientDomain.ClientId
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId
import pl.iterators.epoca.stats.domain.StatsDomain.{StatsEntriesCount, WorkUnitPivotTableKey, WorkUnitPivotTableValue}
import pl.iterators.epoca.stats.domain.WorkUnitPivotTableRow
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService
import pl.iterators.epoca.stats.services.WorkUnitPivotTableService.{WorkUnitPivotTableRequest, WorkUnitPivotTableResult}
import pl.iterators.epoca.user.domain.UserDomain.UserId
import pl.iterators.epoca.utils.RouteDocSpec
import pl.iterators.epoca.utils.UriLengthTestSupport.{FormerMaxUriLength, SupportedMaxUriLength, withQuery}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.{WorkUnitDate, WorkUnitEntryType}
import pl.iterators.epoca.workunit.repositories.WorkUnitRepository.WorkUnitFilters

import java.time.LocalDate
import java.util.UUID

class PostPivotTableWorkUnitRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[WorkUnitPivotTableRequest, Seq[WorkUnitPivotTableRow]] =
    RouteRepresentation[WorkUnitPivotTableRequest, Seq[WorkUnitPivotTableRow]](
      "Generate pivot table for work unit entity - input should be non-empty list of keys and values.\n" +
        "Parameters dateFrom, dateTo, projectId - list, userId - list, clientId - list",
      "POST",
      "/organizations/{organizationSlug}/pivot-tables/work-units",
      List(
        RouteParameterRepresentation[LocalDate]("dateFrom", false, LocalDate.now(), _.toString),
        RouteParameterRepresentation[LocalDate]("dateTo", false, LocalDate.now(), _.toString),
        RouteParameterRepresentation[UUID]("projectId", false, UUID.randomUUID(), _.toString),
        RouteParameterRepresentation[UUID]("userId", false, UUID.randomUUID(), _.toString),
        RouteParameterRepresentation[UUID]("clientId", false, UUID.randomUUID(), _.toString)
      ),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext         = allGenerators[AuthContext].normal.generate
  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate

  val routePath: String = routeRepresentation.path
    .replace("{organizationSlug}", orgSlug)
  val routePathWithParams: String = routeRepresentation.routePathWithAllParameters.replace("{organizationSlug}", orgSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val workUnitPivotTableService: WorkUnitPivotTableService = vmock[WorkUnitPivotTableService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "keep existing pivot keys and expose EntryTypeKey and TaskKey to the documented enum" in {
      WorkUnitPivotTableKey.values
        .map(_.entryName) shouldEqual Seq("UserKey", "ProjectKey", "ClientKey", "YearKey", "MonthYearKey", "DateKey", "EntryTypeKey", "TaskKey")
    }
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }
    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters())
    }
    "return NotFound if service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters())
    }
    "return Forbidden if service returns InsufficientRole" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.InsufficientRole))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters())
    }
    "return BadRequest if service returns KeysRequired" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.KeysRequired))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters())
    }
    "return BadRequest if service returns ValuesRequired" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.KeysRequired))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.BadRequest
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters())
    }
    "return Ok with service data otherwise" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.Ok(responseObject.value)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, WorkUnitFilters())
    }
    "decode EntryTypeKey and encode the grouped entry type" in new LoggedTestCase {
      val requestObject  = WorkUnitPivotTableRequest(Seq(WorkUnitPivotTableKey.EntryTypeKey), Seq(WorkUnitPivotTableValue.EntriesCount))
      val responseObject = Seq(WorkUnitPivotTableRow(entryType = Some(WorkUnitEntryType.Note), entriesCount = Some(StatsEntriesCount(1))))

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject, WorkUnitFilters()))
        .thenReturn(io(WorkUnitPivotTableResult.Ok(responseObject)))

      TestRequest(routePath, requestJson("""{"keys":["EntryTypeKey"],"values":["EntriesCount"]}""")) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json].hcursor.downArray.get[String]("entryType") shouldEqual Right("Note")
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject, WorkUnitFilters())
    }
    "return Ok with service data when all optional params provided" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random

      val filter = WorkUnitFilters(
        dateFrom = routeRepresentation.getParamValue[WorkUnitDate]("dateFrom"),
        dateTo = routeRepresentation.getParamValue[WorkUnitDate]("dateTo"),
        projectIds = routeRepresentation.getParamValue[ProjectId]("projectId").map(Seq(_)),
        userIds = routeRepresentation.getParamValue[UserId]("userId").map(Seq(_)),
        clientIds = routeRepresentation.getParamValue[ClientId]("clientId").map(Seq(_))
      )

      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, filter))
        .thenReturn(io(WorkUnitPivotTableResult.Ok(responseObject.value)))

      TestRequest(routePathWithParams, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqualJson responseObject.getJsonString
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, filter)
    }

    "preserve repeated filters from a URI above the former limit" in new LoggedTestCase {
      val requestObject  = routeRepresentation.request.random
      val responseObject = routeRepresentation.response.random
      val projectIds     = Seq.fill(20)(ProjectId(UUID.randomUUID()))
      val userIds        = Seq.fill(20)(UserId(UUID.randomUUID()))
      val clientIds      = Seq.fill(20)(ClientId(UUID.randomUUID()))
      val longRoutePath =
        withQuery(routePath, "projectId" -> projectIds.map(_.toString), "userId" -> userIds.map(_.toString), "clientId" -> clientIds.map(_.toString))
      val longRouteUri = Uri(path = Uri.Path(routePath), queryString = Some(longRoutePath.drop(routePath.length + 1)))
      val filter       = WorkUnitFilters(projectIds = Some(projectIds.reverse), userIds = Some(userIds.reverse), clientIds = Some(clientIds.reverse))

      (longRoutePath.length > FormerMaxUriLength) shouldEqual true
      (longRoutePath.length <= SupportedMaxUriLength) shouldEqual true
      when(workUnitPivotTableService.buildPivotTable(auth, orgSlug, requestObject.value, filter))
        .thenReturn(io(WorkUnitPivotTableResult.Ok(responseObject.value)))

      HttpRequest(HttpMethods.POST, longRouteUri, entity = requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      verify(workUnitPivotTableService).buildPivotTable(auth, orgSlug, requestObject.value, filter)
    }
  }
}
