package pl.iterators.epoca.oauth.policy

import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthScope
import pl.iterators.epoca.oauth.policy.OAuthOperationRegistry.OAuthOperationClassification._

class OAuthOperationRegistrySpec extends SpecificationLike {
  private val sut = OAuthOperationRegistry.default

  "OAuth operation registry" should {
    "keep all manager override mutations and history human-only" in {
      val base = "/api/v1/organizations/acme/work-units/2d8a8c31-c259-4210-a33b-2f1cae4945ef"
      (sut.classify(HttpMethods.GET, s"$base/override") shouldEqual HumanOnly) and
        (sut.classify(HttpMethods.PUT, s"$base/override") shouldEqual HumanOnly) and
        (sut.classify(HttpMethods.DELETE, s"$base/override") shouldEqual HumanOnly) and
        (sut.classify(HttpMethods.POST, s"$base/override/undo") shouldEqual HumanOnly) and
        (sut.classify(HttpMethods.GET, s"$base/override-history") shouldEqual HumanOnly)
    }

    "contain the fourteen method/path/scope mappings without the retired PM filter route" in {
      val expected = Set(
        OAuthHttpOperation(HttpMethods.GET, "/organizations")                                                    -> OAuthScope.OrganizationRead,
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{slug}")                                             -> OAuthScope.OrganizationRead,
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/users")                           -> OAuthScope.UsersRead,
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{slug}/users/{userId}/details")                      -> OAuthScope.UsersRead,
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/clients")                         -> OAuthScope.ClientsRead,
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/projects")                        -> OAuthScope.ProjectsRead,
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/projects/{projectId}")            -> OAuthScope.ProjectsRead,
        OAuthHttpOperation(HttpMethods.GET, "/organizations/{organizationSlug}/work-units")                      -> OAuthScope.WorkUnitsRead,
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/work-units")                     -> OAuthScope.WorkUnitsWrite,
        OAuthHttpOperation(HttpMethods.PATCH, "/organizations/{organizationSlug}/work-units/{workUnitId}")       -> OAuthScope.WorkUnitsWrite,
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/pivot-tables/work-units")        -> OAuthScope.ReportsRead,
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/pivot-tables/work-units/query")  -> OAuthScope.ReportsRead,
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/work-units/export")              -> OAuthScope.ReportsExport,
        OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/pivot-tables/work-units/export") -> OAuthScope.ReportsExport
      )

      sut.oauthOperations.map(operation => operation.http -> operation.requiredScope).toSet shouldEqual expected
      sut.oauthOperations.map(_.id).distinct should haveSize(14)
    }

    "resolve concrete mapped paths and distinguish explicit human-only and unclassified operations" in {
      val mapped = sut.classify(HttpMethods.GET, "/api/v1/organizations/acme/projects/2d8a8c31-c259-4210-a33b-2f1cae4945ef")

      (mapped must beLike { case OAuthEnabled(operation) => operation.requiredScope shouldEqual OAuthScope.ProjectsRead }) and
        (sut.classify(HttpMethods.POST, "/api/v1/organizations/acme/oauth-clients") shouldEqual HumanOnly) and
        (sut.classify(HttpMethods.GET, "/api/v1/organizations/acme/new-protected-route") shouldEqual Unclassified) and
        (sut.classify(HttpMethods.POST, "/api/v1/oauth/token") shouldEqual Unprotected)
    }

    "fail coverage when a protected OpenAPI operation is neither mapped nor explicitly human-only" in {
      val unknown = OAuthHttpOperation(HttpMethods.POST, "/organizations/{organizationSlug}/new-protected-route")

      sut.validateProtectedInventory(sut.classifiedProtectedOperations + unknown) shouldEqual Left(Set(unknown))
      sut.validateProtectedInventory(sut.classifiedProtectedOperations) shouldEqual Right(())
    }

    "keep every fixture-control route out of the OAuth and protected-operation inventories" in {
      val rendered = (sut.oauthOperations.map(_.http.pathTemplate) ++ sut.classifiedProtectedOperations.map(_.pathTemplate)).mkString("\n")

      rendered must not(contain("ct522-fixture"))
      sut.classify(HttpMethods.POST, "/ct522-fixture/v1/runs") shouldEqual Unprotected
    }
  }
}
