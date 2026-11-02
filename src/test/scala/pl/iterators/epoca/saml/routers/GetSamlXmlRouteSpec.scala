package pl.iterators.epoca.saml.routers

import org.apache.pekko.http.scaladsl.model.StatusCodes
import pl.iterators.baklava.core.model.RouteRepresentation
import pl.iterators.epoca.auth.domain.AuthContext
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.services.SamlConfigXmlService
import pl.iterators.epoca.saml.services.SamlConfigXmlService.SamlConfigXmlFindResult
import pl.iterators.epoca.utils.RouteDocSpec

class GetSamlXmlRouteSpec extends RouteDocSpec {
  override val routeRepresentation: RouteRepresentation[Unit, String] =
    RouteRepresentation[Unit, String](
      "Returns XML of metadata related to SAML - used my external identity provider.",
      "GET",
      "/organizations/{organizationSlug}/saml/metadata.xml"
    )

  val orgSlug: OrganizationSlug = allGenerators[OrganizationSlug].normal.generate
  val routePath: String         = routeRepresentation.path.replace("{organizationSlug}", orgSlug)

  trait NoLoggedTestCase extends BaseScope {
    override lazy val authContextForTest: Option[AuthContext]    = None
    override lazy val samlConfigXmlService: SamlConfigXmlService = vmock[SamlConfigXmlService]
  }

  routeRepresentation.name should {
    "return NotFound if service return OrganizationNotFound" in new NoLoggedTestCase {
      when(samlConfigXmlService.find(orgSlug)).thenReturn(io(SamlConfigXmlFindResult.OrganizationNotFound))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlConfigXmlService).find(orgSlug)
    }
    "return NotFound if service return SamlConfigNotFound" in new NoLoggedTestCase {
      when(samlConfigXmlService.find(orgSlug)).thenReturn(io(SamlConfigXmlFindResult.SamlConfigNotFound))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
      verify(samlConfigXmlService).find(orgSlug)
    }
    "return Forbidden if service return SamlConfigNotFound" in new NoLoggedTestCase {
      when(samlConfigXmlService.find(orgSlug)).thenReturn(io(SamlConfigXmlFindResult.SamlDisabled))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
      verify(samlConfigXmlService).find(orgSlug)
    }
    "return OK with service data otherwise" in new NoLoggedTestCase {
      val responseObject = routeRepresentation.response.random
      when(samlConfigXmlService.find(orgSlug)).thenReturn(io(SamlConfigXmlFindResult.Ok(responseObject.value)))
      TestRequest(routePath, emptyString) ~> allRoutes ~> check {
        response.status shouldEqual StatusCodes.OK
        response.entity.contentType.toString() shouldEqual "text/xml; charset=UTF-8"
      }
      verify(samlConfigXmlService).find(orgSlug)
    }
  }
}
