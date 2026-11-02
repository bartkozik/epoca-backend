package pl.iterators.epoca.`export`.routers

import cats.effect.IO
import fs2.Stream
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.scaladsl.Sink
import org.mockito.ArgumentCaptor
import pl.iterators.baklava.core.model.{RouteParameterRepresentation, RouteRepresentation, RouteSecurity}
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.export.domain.SpreadsheetDomain.{SpreadsheetColumnName, SpreadsheetFormat, SpreadsheetName}
import pl.iterators.epoca.export.services.ExportWorkUnitService
import pl.iterators.epoca.export.services.ExportWorkUnitService.{ExportWorkUnitRequest, ExportWorkUnitResult}
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.utils.RouteDocSpec

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

class PostWorkUnitExportRouteSpec extends RouteDocSpec {
  val format: SpreadsheetFormat = SpreadsheetFormat.Csv
  override val routeRepresentation: RouteRepresentation[ExportWorkUnitRequest, Unit] =
    RouteRepresentation[ExportWorkUnitRequest, Unit](
      "List work units and exports it as xlxs or csv file - format parameter required. Requesting users' work units require admin/owner/manager access.\n",
      "POST",
      "/organizations/{organizationSlug}/work-units/export",
      List(RouteParameterRepresentation[SpreadsheetFormat]("format", true, format, _.toString)),
      authentication = List(RouteSecurity.Bearer())
    )

  val auth: AuthContext                = allGenerators[AuthContext].normal.generate
  val orgSlug: OrganizationSlug        = allGenerators[OrganizationSlug].normal.generate
  val routePath: String                = routeRepresentation.routePathWithRequiredParameters.replace("{organizationSlug}", orgSlug)
  val exampleFilename: SpreadsheetName = SpreadsheetName("filename.xls")
  val byteMsg: List[Byte]              = "user".getBytes("UTF-8").toList
  val exampleSource: Stream[IO, Byte]  = Stream.emits(byteMsg)
  val legacyRequestJson: String =
    """{"columnNames":{},"ids":[],"dateFrom":null,"dateTo":null,"projectIds":[],"userIds":[],"clientIds":[]}"""
  val customRequestJson: String =
    """{"columnNames":{"entryType":"Typ wpisu"},"ids":[],"dateFrom":null,"dateTo":null,"projectIds":[],"userIds":[],"clientIds":[]}"""
  val pmRequestJson: String =
    """{"columnNames":{"pmClientName":"PM Client","pmProjectName":"PM Project","pmTaskId":"PM Task"},"ids":[],"dateFrom":null,"dateTo":null,"projectIds":[],"userIds":[],"clientIds":[],"includePmClassification":true}"""

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext] = None
  }

  trait LoggedTestCase extends BaseScope {
    override lazy val exportWorkUnitService: ExportWorkUnitService = vmock[ExportWorkUnitService]

    override val authContextForTest: Option[AuthContext] = Some(auth)
  }

  routeRepresentation.name should {
    "be inaccessible for no-logged user" in new NoLoggedTestCase {
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldBeInaccessible
      }
    }

    "return NotFound if service returns NoAccessToOrganization" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(exportWorkUnitService.`export`(auth, orgSlug, requestObject.value, format))
        .thenReturn(io(ExportWorkUnitResult.NoAccessToOrganization))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(exportWorkUnitService).`export`(auth, orgSlug, requestObject.value, format)
    }

    "return NotFound if service returns OrganizationNotFound" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(exportWorkUnitService.`export`(auth, orgSlug, requestObject.value, format))
        .thenReturn(io(ExportWorkUnitResult.OrganizationNotFound))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }

      verify(exportWorkUnitService).`export`(auth, orgSlug, requestObject.value, format)
    }

    "return Forbidden if service returns OverrideForbidden" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(exportWorkUnitService.`export`(auth, orgSlug, requestObject.value, format))
        .thenReturn(io(ExportWorkUnitResult.OverrideForbidden))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      verify(exportWorkUnitService).`export`(auth, orgSlug, requestObject.value, format)
    }

    "return Ok with service data when service response is delayed" in new LoggedTestCase {
      val requestObject = routeRepresentation.request.random
      when(exportWorkUnitService.`export`(auth, orgSlug, requestObject.value, format))
        .thenReturn(IO.sleep(1500.millis).map(_ => ExportWorkUnitResult.Ok(exampleFilename, exampleSource, format)))

      TestRequest(routePath, requestJson(requestObject.getJsonString)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        val listAttachmentF = response.entity.dataBytes.runWith(Sink.fold(List.empty[Byte])((acc, byteString) => acc ++ byteString.toList))
        Await.result(listAttachmentF, 3.seconds) shouldEqual byteMsg
      }

      verify(exportWorkUnitService).`export`(auth, orgSlug, requestObject.value, format)
    }

    "decode legacy and localized raw requests with fallback or custom entry type column name" in new LoggedTestCase {
      val requests = Seq(legacyRequestJson -> SpreadsheetColumnName("entryType"), customRequestJson -> SpreadsheetColumnName("Typ wpisu"))
      when(exportWorkUnitService.`export`(eqTo(auth), eqTo(orgSlug), any[ExportWorkUnitRequest], eqTo(format)))
        .thenReturn(io(ExportWorkUnitResult.Ok(exampleFilename, exampleSource, format)))

      requests.foreach { case (json, _) =>
        TestRequest(routePath, requestJson(json)) ~> allRoutes ~> check {
          response.status shouldEqual StatusCodes.OK
        }
      }

      val captor = ArgumentCaptor.forClass(classOf[ExportWorkUnitRequest])
      verify(exportWorkUnitService, times(requests.size)).`export`(eqTo(auth), eqTo(orgSlug), captor.capture(), eqTo(format))
      captor.getAllValues.asScala.map(_.columnNames.asMap.get("entryType")) shouldEqual requests.map(request => Some(request._2))
    }

    "ignore retired PM export options without adding parallel columns" in new LoggedTestCase {
      when(exportWorkUnitService.`export`(eqTo(auth), eqTo(orgSlug), any[ExportWorkUnitRequest], eqTo(format)))
        .thenReturn(io(ExportWorkUnitResult.Ok(exampleFilename, exampleSource, format)))

      TestRequest(routePath, requestJson(pmRequestJson)) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
      }

      val captor = ArgumentCaptor.forClass(classOf[ExportWorkUnitRequest])
      verify(exportWorkUnitService).`export`(eqTo(auth), eqTo(orgSlug), captor.capture(), eqTo(format))
      captor.getValue.columnNames.asMap.keySet.intersect(Set("pmClientName", "pmProjectName", "pmTaskId")) should beEmpty
    }
  }
}
