package pl.iterators.epoca.utils

import io.swagger.v3.core.util.Yaml
import org.specs2.mutable.Specification

import scala.jdk.CollectionConverters._

class InvitationOpenApiContractsSpec extends Specification {
  "invitation OpenAPI contract" should {
    "publish explicit access, joined, and admin projections and required browser contracts" in {
      val root   = Yaml.mapper().readTree(InvitationOpenApiContracts.publish("""{"paths":{},"components":{}}"""))
      val access = root.at("/components/schemas/InvitationAccess/properties")
      access.fieldNames().asScala.toSet mustEqual Set(
        "organization",
        "email",
        "firstName",
        "lastName",
        "roles",
        "revision",
        "expiresAt",
        "csrfToken",
        "nextAction",
        "ssoEnabled"
      )
      root.at("/components/schemas/InvitationAccess/additionalProperties").asBoolean() must beFalse
      root.at("/paths/~1invitation-access~1exchange/post/responses/200/headers/Set-Cookie/schema/type").asText() mustEqual "string"
      root.at("/paths/~1invitation-access/delete/responses/204/headers/Set-Cookie/schema/type").asText() mustEqual "string"
      root.at("/components/schemas/InvitationJoined/properties").fieldNames().asScala.toSet mustEqual Set(
        "organizationSlug",
        "accessToken",
        "refreshToken"
      )
      val create = root.at("/paths/~1organizations~1{organizationSlug}~1invitations/post")
      create.path("parameters").elements().asScala.map(_.path("name").asText()).toSet must contain("version", "Idempotency-Key")
      val accept = root.at("/paths/~1invitation-access~1accept/post")
      accept.path("parameters").elements().asScala.filter(_.path("required").asBoolean()).map(_.path("name").asText()).toSet must contain(
        "Origin",
        "X-CSRF-Token",
        "Device-Id"
      )
      root.at("/components/schemas/InvitationAdminV2/properties").has("tokenHash") must beFalse
    }
    "retain the explicit legacy collection schemas alongside version 2" in {
      val input =
        """{"paths":{"/organizations/{organizationSlug}/invitations":{"get":{"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/LegacyInvitationPage"}}}}}}}}},"components":{}}"""
      val root         = Yaml.mapper().readTree(InvitationOpenApiContracts.publish(input))
      val alternatives = root.at("/paths/~1organizations~1{organizationSlug}~1invitations/get/responses/200/content/application~1json/schema/oneOf")
      alternatives.size() mustEqual 2
      alternatives.get(0).path("$ref").asText() mustEqual "#/components/schemas/LegacyInvitationPage"
      alternatives.get(1).path("$ref").asText() mustEqual "#/components/schemas/InvitationPageV2"
    }
  }
}
