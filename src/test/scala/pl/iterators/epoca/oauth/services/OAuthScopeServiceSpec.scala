package pl.iterators.epoca.oauth.services

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthScope, OAuthScopeError}

class OAuthScopeServiceSpec extends SpecificationLike {
  private val catalog: Vector[OAuthScope] = Vector(
    OAuthScope.OrganizationRead,
    OAuthScope.UsersRead,
    OAuthScope.ClientsRead,
    OAuthScope.ProjectsRead,
    OAuthScope.WorkUnitsRead,
    OAuthScope.WorkUnitsWrite,
    OAuthScope.ReportsRead,
    OAuthScope.ReportsExport
  )

  "OAuth scopes" should {
    "expose the exact closed catalog in canonical order and parse case-sensitively" in {
      OAuthScope.values shouldEqual catalog
      catalog.map(_.value) shouldEqual Vector(
        "organization:read",
        "users:read",
        "clients:read",
        "projects:read",
        "work-units:read",
        "work-units:write",
        "reports:read",
        "reports:export"
      )
      OAuthScope.fromValue("reports:export") shouldEqual Some(OAuthScope.ReportsExport)
      OAuthScope.fromValue("Reports:Export") should beNone
      OAuthScope.fromValue("unknown:read") should beNone
    }

    "reject an empty management grant" in {
      OAuthScopeService.normalizeGranted(Set.empty) shouldEqual Left(OAuthScopeError.EmptyGrantedScopes)
    }

    "normalize management grants and render only in canonical catalog order" in {
      val scopes: Set[OAuthScope] = Set(OAuthScope.ReportsExport, OAuthScope.ProjectsRead, OAuthScope.ReportsExport)

      OAuthScopeService.normalizeGranted(scopes) shouldEqual Right(Set(OAuthScope.ProjectsRead, OAuthScope.ReportsExport))
      OAuthScopeService.render(scopes) shouldEqual "projects:read reports:export"
    }

    "treat an omitted token scope as all granted scopes" in {
      val granted: Set[OAuthScope] = Set(OAuthScope.WorkUnitsRead, OAuthScope.OrganizationRead)

      OAuthScopeService.resolveRequested(None, granted) shouldEqual Right(granted)
    }

    "reject a supplied blank, unknown, case-variant, or wider token scope" in {
      val granted: Set[OAuthScope] = Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead)

      OAuthScopeService.resolveRequested(Some("  \t "), granted) shouldEqual Left(OAuthScopeError.InvalidScope)
      OAuthScopeService.resolveRequested(Some("projects:read unknown:read"), granted) shouldEqual Left(OAuthScopeError.InvalidScope)
      OAuthScopeService.resolveRequested(Some("Projects:Read"), granted) shouldEqual Left(OAuthScopeError.InvalidScope)
      OAuthScopeService.resolveRequested(Some("projects:read reports:export"), granted) shouldEqual Left(OAuthScopeError.InvalidScope)
    }

    "normalize and deduplicate a nonempty whitespace-delimited granted subset" in {
      val granted: Set[OAuthScope] = Set(OAuthScope.OrganizationRead, OAuthScope.ProjectsRead, OAuthScope.ReportsRead)

      val result = OAuthScopeService.resolveRequested(Some("reports:read\tprojects:read  reports:read"), granted)

      result shouldEqual Right(Set(OAuthScope.ProjectsRead, OAuthScope.ReportsRead))
      result.map(OAuthScopeService.render) shouldEqual Right("projects:read reports:read")
    }
  }
}
