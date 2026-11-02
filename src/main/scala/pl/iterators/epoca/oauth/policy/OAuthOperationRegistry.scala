package pl.iterators.epoca.oauth.policy

import org.apache.pekko.http.scaladsl.model.{HttpMethod, HttpMethods}
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthScope
import pl.iterators.epoca.oauth.policy.OAuthOperationRegistry.OAuthOperationClassification._

final case class OAuthHttpOperation(method: HttpMethod, pathTemplate: String)

sealed trait OAuthProjectStrategy
object OAuthProjectStrategy {
  case object OrganizationBound  extends OAuthProjectStrategy
  case object ProjectCollection  extends OAuthProjectStrategy
  case object DirectProject      extends OAuthProjectStrategy
  case object WorkUnitCollection extends OAuthProjectStrategy
  case object DirectWorkUnit     extends OAuthProjectStrategy
  case object ReportCollection   extends OAuthProjectStrategy
}

final case class OAuthOperation(
  id: String,
  http: OAuthHttpOperation,
  requiredScope: OAuthScope,
  projectStrategy: OAuthProjectStrategy)

final class OAuthOperationRegistry private (val oauthOperations: Vector[OAuthOperation], val humanOnlyOperations: Set[OAuthHttpOperation]) {

  val classifiedProtectedOperations: Set[OAuthHttpOperation] = oauthOperations.map(_.http).toSet ++ humanOnlyOperations

  def classify(method: HttpMethod, effectivePath: String): OAuthOperationRegistry.OAuthOperationClassification = {
    val normalizedPath = normalizeEffectivePath(effectivePath)
    oauthOperations.find(operation => operation.http.method == method && matches(operation.http.pathTemplate, normalizedPath)) match {
      case Some(operation) => OAuthEnabled(operation)
      case None if humanOnlyOperations.exists(operation => operation.method == method && matches(operation.pathTemplate, normalizedPath)) => HumanOnly
      case None if protectedPrefix(normalizedPath) => Unclassified
      case None                                    => Unprotected
    }
  }

  def validateProtectedInventory(inventory: Set[OAuthHttpOperation]): Either[Set[OAuthHttpOperation], Unit] = {
    val drift = (inventory -- classifiedProtectedOperations) ++ (classifiedProtectedOperations -- inventory)
    Either.cond(drift.isEmpty, (), drift)
  }

  private def normalizeEffectivePath(path: String): String = {
    val segments = pathSegments(path)
    val normalized = segments match {
      case Vector("api", "v1", tail @ _*) => tail.toVector
      case other                          => other
    }
    "/" + normalized.mkString("/")
  }

  private def matches(template: String, actual: String): Boolean = {
    val templateSegments = pathSegments(template)
    val actualSegments   = pathSegments(actual)
    templateSegments.size == actualSegments.size && templateSegments.zip(actualSegments).forall { case (expected, received) =>
      (expected.startsWith("{") && expected.endsWith("}") && received.nonEmpty) || expected == received
    }
  }

  private def protectedPrefix(path: String): Boolean =
    pathSegments(path).headOption.exists(Set("auth", "invitations", "invitation-access", "metrics", "organizations", "users"))

  private def pathSegments(path: String): Vector[String] =
    path.split('/').iterator.filter(_.nonEmpty).toVector
}

object OAuthOperationRegistry {
  sealed trait OAuthOperationClassification
  object OAuthOperationClassification {
    final case class OAuthEnabled(operation: OAuthOperation) extends OAuthOperationClassification
    case object HumanOnly                                    extends OAuthOperationClassification
    case object Unclassified                                 extends OAuthOperationClassification
    case object Unprotected                                  extends OAuthOperationClassification
  }

  import OAuthProjectStrategy._

  lazy val default: OAuthOperationRegistry = new OAuthOperationRegistry(oauthOperations, humanOnlyOperations)

  private val oauthOperations = Vector(
    operation("organizations.list", HttpMethods.GET, "/organizations", OAuthScope.OrganizationRead, OrganizationBound),
    operation("organizations.get", HttpMethods.GET, "/organizations/{slug}", OAuthScope.OrganizationRead, OrganizationBound),
    operation("organization-users.list", HttpMethods.GET, "/organizations/{organizationSlug}/users", OAuthScope.UsersRead, ProjectCollection),
    operation("organization-users.details", HttpMethods.GET, "/organizations/{slug}/users/{userId}/details", OAuthScope.UsersRead, ProjectCollection),
    operation("business-clients.list", HttpMethods.GET, "/organizations/{organizationSlug}/clients", OAuthScope.ClientsRead, ProjectCollection),
    operation("projects.list", HttpMethods.GET, "/organizations/{organizationSlug}/projects", OAuthScope.ProjectsRead, ProjectCollection),
    operation("projects.get", HttpMethods.GET, "/organizations/{organizationSlug}/projects/{projectId}", OAuthScope.ProjectsRead, DirectProject),
    operation("work-units.list", HttpMethods.GET, "/organizations/{organizationSlug}/work-units", OAuthScope.WorkUnitsRead, WorkUnitCollection),
    operation("work-units.create", HttpMethods.POST, "/organizations/{organizationSlug}/work-units", OAuthScope.WorkUnitsWrite, WorkUnitCollection),
    operation(
      "work-units.update",
      HttpMethods.PATCH,
      "/organizations/{organizationSlug}/work-units/{workUnitId}",
      OAuthScope.WorkUnitsWrite,
      DirectWorkUnit
    ),
    operation(
      "pivot-work-units.run",
      HttpMethods.POST,
      "/organizations/{organizationSlug}/pivot-tables/work-units",
      OAuthScope.ReportsRead,
      ReportCollection
    ),
    operation(
      "pivot-work-units.query",
      HttpMethods.POST,
      "/organizations/{organizationSlug}/pivot-tables/work-units/query",
      OAuthScope.ReportsRead,
      ReportCollection
    ),
    operation(
      "work-units.export",
      HttpMethods.POST,
      "/organizations/{organizationSlug}/work-units/export",
      OAuthScope.ReportsExport,
      ReportCollection
    ),
    operation(
      "pivot-work-units.export",
      HttpMethods.POST,
      "/organizations/{organizationSlug}/pivot-tables/work-units/export",
      OAuthScope.ReportsExport,
      ReportCollection
    )
  )

  private val humanOnlyOperations: Set[OAuthHttpOperation] = Set(
    http(HttpMethods.GET, "/organizations/{organizationSlug}/work-units/{workUnitId}/override"),
    http(HttpMethods.PUT, "/organizations/{organizationSlug}/work-units/{workUnitId}/override"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}/work-units/{workUnitId}/override"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/work-units/{workUnitId}/override/undo"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/work-units/{workUnitId}/override-history"),
    http(HttpMethods.DELETE, "/auth/refresh-tokens/{deviceId}"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}/clients/{clientId}"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}/invitations/{id}"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/invitations/{id}"),
    http(HttpMethods.PATCH, "/organizations/{organizationSlug}/invitations/{id}"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/invitations/{id}/resend"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}/projects/{projectId}"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}/projects/{projectId}/users/{userId}"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}/work-units/{workUnitId}"),
    http(HttpMethods.DELETE, "/organizations/{organizationSlug}/work-units/{workUnitId}/pm-classification"),
    http(HttpMethods.DELETE, "/users/identities/{identityId}"),
    http(HttpMethods.DELETE, "/users/{userId}"),
    http(HttpMethods.GET, "/auth/refresh-tokens"),
    http(HttpMethods.GET, "/invitations"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/invitations"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/oauth-clients"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/oauth-clients/{clientId}"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/oauth-clients/{clientId}/audit-events"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/work-units/{workUnitId}/pm-classification"),
    http(HttpMethods.GET, "/organizations/{organizationSlug}/work-units/{workUnitId}/pm-classification/history"),
    http(HttpMethods.GET, "/users"),
    http(HttpMethods.GET, "/users/identities"),
    http(HttpMethods.GET, "/users/{userId}"),
    http(HttpMethods.PATCH, "/auth/password"),
    http(HttpMethods.PATCH, "/organizations/{organizationSlug}"),
    http(HttpMethods.PATCH, "/organizations/{organizationSlug}/clients/{clientId}"),
    http(HttpMethods.PATCH, "/organizations/{organizationSlug}/oauth-clients/{clientId}"),
    http(HttpMethods.PATCH, "/organizations/{organizationSlug}/projects/all/users/{userId}"),
    http(HttpMethods.PATCH, "/organizations/{organizationSlug}/projects/{projectId}"),
    http(HttpMethods.PATCH, "/users/identities/{identityId}"),
    http(HttpMethods.PATCH, "/users/{userId}"),
    http(HttpMethods.POST, "/invitations"),
    http(HttpMethods.POST, "/metrics/frontend/error"),
    http(HttpMethods.POST, "/organizations"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/clients"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/clients/{clientId}/unarchive"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/invitations"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/oauth-clients"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/oauth-clients/{clientId}/revoke"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/oauth-clients/{clientId}/rotate-secret"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/projects"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/projects/all/users/{userId}"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/projects/{projectId}/unarchive"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/projects/{projectId}/users/{userId}"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/users/assign"),
    http(HttpMethods.POST, "/organizations/{organizationSlug}/users/deassign"),
    http(HttpMethods.POST, "/users/identities"),
    http(HttpMethods.POST, "/users/identities/{identityId}/main"),
    http(HttpMethods.PUT, "/organizations/{organizationSlug}/work-units/order"),
    http(HttpMethods.PUT, "/organizations/{organizationSlug}/work-units/{workUnitId}/pm-classification")
  )

  private def operation(
    id: String,
    method: HttpMethod,
    path: String,
    scope: OAuthScope,
    strategy: OAuthProjectStrategy
  ): OAuthOperation = OAuthOperation(id, http(method, path), scope, strategy)

  private def http(method: HttpMethod, path: String): OAuthHttpOperation = OAuthHttpOperation(method, path)
}
