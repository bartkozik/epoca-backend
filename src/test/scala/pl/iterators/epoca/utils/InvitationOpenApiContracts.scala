package pl.iterators.epoca.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.core.util.Yaml

import scala.jdk.CollectionConverters._

object InvitationOpenApiContracts {
  def publish(input: String): String = {
    val root       = Yaml.mapper().readTree(input).asInstanceOf[ObjectNode]
    val contract   = Yaml.mapper().readTree(definitions)
    val components = root.withObject("/components")
    val schemas    = components.withObject("/schemas")
    contract.path("schemas").properties().asScala.foreach(e => schemas.set[JsonNode](e.getKey, e.getValue))
    components
      .withObject("/securitySchemes")
      .set[JsonNode]("invitationCookie", Yaml.mapper().readTree("""{"type":"apiKey","in":"cookie","name":"epoca_invitation_access"}"""))
    val paths = root.withObject("/paths")
    contract.path("operations").elements().asScala.foreach { data =>
      val path      = data.path("path").asText()
      val method    = data.path("method").asText()
      val public    = data.path("public").asBoolean()
      val status    = data.path("status").asText()
      val existing  = Option(paths.get(path)).filter(_.isObject).map(_.asInstanceOf[ObjectNode]).getOrElse(Yaml.mapper().createObjectNode())
      val prior     = existing.path(method)
      val operation = Yaml.mapper().createObjectNode()
      operation.put("summary", data.path("summary").asText())
      operation.put(
        "description",
        if (public) "Cookie context; optional fresh Human authentication is validated by the server. OAuth is denied. Responses are no-store."
        else "Requires current active Human Owner membership, including history/cancellation while disabled. No global-admin or OAuth bypass. Inaccessible resources return 404."
      )
      val security = operation.putArray("security")
      if (path != "/invitation-access/exchange") security.addObject().putArray(if (public) "invitationCookie" else "humanBearer")
      val params = operation.putArray("parameters")
      data.path("parameters").elements().asScala.foreach(p => params.add(contract.path("parameters").path(p.asText())))
      val responses = operation.putObject("responses")
      Seq(
        "400" -> "Invalid request",
        "401" -> "Sign-in required",
        "403" -> "Invalid browser proof or identity mismatch",
        "404" -> "Invalid invitation or inaccessible resource",
        "409" -> "Invitation conflict",
        "429" -> "Rate limited",
        "503" -> "Feature disabled or post-commit session issuance unavailable"
      ).foreach { case (code, description) => responses.putObject(code).put("description", description) }
      responses.withObject("/429/headers/Retry-After/schema").put("type", "integer")
      val success = responses.putObject(status)
      success.put("description", if (status == "204") "No content" else "Safe response")
      if (public && Set("/invitation-access", "/invitation-access/exchange", "/invitation-access/saml")(path)) {
        success
          .withObject("/headers/Set-Cookie")
          .put(
            "description",
            "Server-issued host-only HttpOnly cookie, scoped to the configured API path. Secure except explicit loopback development; SameSite=Lax by default. DELETE clears the invitation context cookie; SAML start issues a nonce cookie."
          )
          .withObject("/schema")
          .put("type", "string")
      }
      if (!data.path("response").isNull) success.withObject("/content/application~1json").set[JsonNode]("schema", data.path("response"))
      if (!data.path("request").isNull) {
        operation.withObject("/requestBody").put("required", true)
        operation.withObject("/requestBody/content/application~1json").set[JsonNode]("schema", data.path("request"))
      }
      if (public)
        responses
          .properties()
          .asScala
          .foreach(
            _.getValue.asInstanceOf[ObjectNode].withObject("/headers/Cache-Control/schema").put("type", "string").putArray("enum").add("no-store")
          )
      if (path == "/invitation-access/password" || path == "/invitation-access/accept")
        responses.withObject("/503/content/application~1json/schema").put("$ref", "#/components/schemas/InvitationSessionIssueFailed")
      if (path == "/organizations/{organizationSlug}/invitations") {
        Seq(
          "/requestBody/content/application~1json",
          if (method == "post") "/responses/201/content/application~1json" else "/responses/200/content/application~1json"
        ).foreach { pointer =>
          val legacy = prior.at(pointer).path("schema")
          if (!legacy.isMissingNode) {
            val destination = operation.at(pointer).asInstanceOf[ObjectNode]
            val union       = Yaml.mapper().createObjectNode()
            union.putArray("oneOf").add(legacy).add(destination.path("schema"))
            destination.set[JsonNode]("schema", union)
          }
        }
      }
      existing.set[JsonNode](method, operation)
      paths.set[JsonNode](path, existing)
    }
    Yaml.mapper().writeValueAsString(root)
  }
  private val definitions = """{
  "schemas": {
    "InvitationCreateV2": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "firstName": {"type": "string", "minLength": 1, "maxLength": 200},
        "lastName": {"type": "string", "minLength": 1, "maxLength": 200},
        "email": {"type": "string", "format": "email"},
        "role": {"type": "string", "enum": ["Owner", "Manager", "Employee", "External"]},
        "locale": {"type": "string", "enum": ["pl", "en"]}
      },
      "required": ["firstName", "lastName", "email", "role", "locale"]
    },
    "InvitationPatchV2": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "expectedRevision": {"type": "string", "pattern": "^(0|[1-9][0-9]*)$", "description": "Decimal string; never a JSON number."},
        "firstName": {"type": "string", "minLength": 1, "maxLength": 200},
        "lastName": {"type": "string", "minLength": 1, "maxLength": 200},
        "role": {"type": "string", "enum": ["Owner", "Manager", "Employee", "External"]},
        "locale": {"type": "string", "enum": ["pl", "en"]}
      },
      "required": ["expectedRevision"]
    },
    "InvitationAdminV2": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "id": {"type": "string", "format": "uuid"},
        "email": {"type": "string"},
        "firstName": {"type": "string", "nullable": true},
        "lastName": {"type": "string", "nullable": true},
        "roles": {"type": "array", "items": {"type": "string"}},
        "protocolVersion": {"type": "integer", "enum": [1, 2]},
        "revision": {"type": "string", "pattern": "^(0|[1-9][0-9]*)$", "description": "Decimal string; never a JSON number."},
        "status": {"type": "string", "enum": ["Pending", "Expired", "Accepted", "Cancelled"]},
        "expiresAt": {"type": "string", "format": "date-time", "nullable": true},
        "delivery": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "status": {"type": "string", "enum": ["NotSent", "Sent", "Failed", "Unknown", "Started"]},
            "lastAttemptAt": {"type": "string", "format": "date-time", "nullable": true},
            "retryAfterSeconds": {"type": "integer", "minimum": 0}
          },
          "required": ["status", "lastAttemptAt", "retryAfterSeconds"]
        },
        "createdAt": {"type": "string", "format": "date-time"},
        "modifiedAt": {"type": "string", "format": "date-time"}
      },
      "required": ["id", "email", "firstName", "lastName", "roles", "protocolVersion", "revision", "status", "expiresAt", "delivery", "createdAt", "modifiedAt"]
    },
    "InvitationPageV2": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "count": {"type": "integer"},
        "results": {"type": "array", "items": {"$ref": "#/components/schemas/InvitationAdminV2"}},
        "featureEnabled": {"type": "boolean"}
      },
      "required": ["count", "results", "featureEnabled"]
    },
    "InvitationAccess": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "organization": {"type": "object", "additionalProperties": false, "properties": {"name": {"type": "string"}, "slug": {"type": "string"}}, "required": ["name", "slug"]},
        "email": {"type": "string"},
        "firstName": {"type": "string", "nullable": true},
        "lastName": {"type": "string", "nullable": true},
        "roles": {"type": "array", "items": {"type": "string"}},
        "revision": {"type": "string", "pattern": "^(0|[1-9][0-9]*)$", "description": "Decimal string; never a JSON number."},
        "expiresAt": {"type": "string", "format": "date-time"},
        "csrfToken": {"type": "string"},
        "nextAction": {"type": "string", "enum": ["CreateAccount", "SignIn", "Accept", "VerifyLegacySso"]},
        "ssoEnabled": {"type": "boolean"}
      },
      "required": ["organization", "email", "firstName", "lastName", "roles", "revision", "expiresAt", "csrfToken", "nextAction", "ssoEnabled"]
    },
    "InvitationJoined": {
      "type": "object",
      "additionalProperties": false,
      "properties": {"organizationSlug": {"type": "string"}, "accessToken": {"type": "string"}, "refreshToken": {"type": "string"}},
      "required": ["organizationSlug", "accessToken", "refreshToken"]
    },
    "InvitationSessionIssueFailed": {
      "type": "object",
      "additionalProperties": false,
      "properties": {"type": {"type": "string", "enum": ["result:invitation:session-issue-failed"]}, "joined": {"type": "boolean", "enum": [true]}},
      "required": ["type", "joined"]
    }
  },
  "parameters": {
    "version0": {
      "name": "version",
      "in": "query",
      "required": false,
      "schema": {"type": "string", "enum": ["2"]},
      "description": "Absent selects the legacy contract for collection/create/detail. Unknown values fail before entity decoding. PATCH/resend are always v2; DELETE with expectedRevision always uses revision checking."
    },
    "IdempotencyKey1": {
      "name": "Idempotency-Key",
      "in": "header",
      "required": false,
      "schema": {"type": "string", "format": "uuid"},
      "description": "Required for v2 create and resend. Replays never send a second email."
    },
    "organizationSlug2": {"name": "organizationSlug", "in": "path", "required": true, "schema": {"type": "string"}, "description": ""},
    "id3": {"name": "id", "in": "query", "required": false, "schema": {"type": "string", "format": "uuid"}, "description": ""},
    "pageSize4": {"name": "pageSize", "in": "query", "required": false, "schema": {"type": "integer", "minimum": 1, "maximum": 200}, "description": ""},
    "pageNumber5": {"name": "pageNumber", "in": "query", "required": false, "schema": {"type": "integer", "minimum": 1}, "description": ""},
    "id6": {"name": "id", "in": "path", "required": true, "schema": {"type": "string", "format": "uuid"}, "description": ""},
    "expectedRevision7": {
      "name": "expectedRevision",
      "in": "query",
      "required": false,
      "schema": {"type": "string", "pattern": "^(0|[1-9][0-9]*)$", "description": "Decimal string; never a JSON number."},
      "description": "Required for v2 DELETE; a supplied value selects revision checking even without version. Legacy DELETE without version/revision remains supported."
    },
    "IdempotencyKey8": {
      "name": "Idempotency-Key",
      "in": "header",
      "required": true,
      "schema": {"type": "string", "format": "uuid"},
      "description": "Required for v2 create and resend. Replays never send a second email."
    },
    "Origin9": {"name": "Origin", "in": "header", "required": true, "schema": {"type": "string"}, "description": "Exactly one configured frontend origin. Never wildcard."},
    "XCSRFToken10": {"name": "X-CSRF-Token", "in": "header", "required": true, "schema": {"type": "string"}, "description": "Required on cookie mutations, with one exact configured Origin."},
    "DeviceId11": {"name": "Device-Id", "in": "header", "required": true, "schema": {"type": "string"}, "description": ""}
  },
  "operations": [
    {
      "path": "/organizations/{organizationSlug}/invitations",
      "method": "post",
      "summary": "Create invitation (explicit version=2)",
      "public": false,
      "status": "201",
      "response": {"$ref": "#/components/schemas/InvitationAdminV2"},
      "request": {"$ref": "#/components/schemas/InvitationCreateV2"},
      "parameters": ["version0", "IdempotencyKey1", "organizationSlug2"]
    },
    {
      "path": "/organizations/{organizationSlug}/invitations",
      "method": "get",
      "summary": "List invitation history (explicit version=2)",
      "public": false,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationPageV2"},
      "request": null,
      "parameters": ["version0", "id3", "pageSize4", "pageNumber5", "organizationSlug2"]
    },
    {
      "path": "/organizations/{organizationSlug}/invitations/{id}",
      "method": "get",
      "summary": "Get invitation detail (version=2)",
      "public": false,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationAdminV2"},
      "request": null,
      "parameters": ["version0", "organizationSlug2", "id6"]
    },
    {
      "path": "/organizations/{organizationSlug}/invitations/{id}",
      "method": "patch",
      "summary": "Edit invitation",
      "public": false,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationAdminV2"},
      "request": {"$ref": "#/components/schemas/InvitationPatchV2"},
      "parameters": ["version0", "organizationSlug2", "id6"]
    },
    {
      "path": "/organizations/{organizationSlug}/invitations/{id}",
      "method": "delete",
      "summary": "Cancel invitation",
      "public": false,
      "status": "204",
      "response": null,
      "request": null,
      "parameters": ["version0", "expectedRevision7", "organizationSlug2", "id6"]
    },
    {
      "path": "/organizations/{organizationSlug}/invitations/{id}/resend",
      "method": "post",
      "summary": "Resend or upgrade a legacy email invitation",
      "public": false,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationAdminV2"},
      "request": {"$ref": "#/components/schemas/InvitationPatchV2"},
      "parameters": ["version0", "IdempotencyKey8", "organizationSlug2", "id6"]
    },
    {
      "path": "/invitation-access/exchange",
      "method": "post",
      "summary": "Exchange email link token",
      "public": true,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationAccess"},
      "request": {"type": "object", "additionalProperties": false, "properties": {"token": {"type": "string"}}, "required": ["token"]},
      "parameters": ["Origin9"]
    },
    {
      "path": "/invitation-access",
      "method": "get",
      "summary": "Reload invitation summary and rotate CSRF",
      "public": true,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationAccess"},
      "request": null,
      "parameters": []
    },
    {
      "path": "/invitation-access",
      "method": "delete",
      "summary": "Revoke only this access context",
      "public": true,
      "status": "204",
      "response": null,
      "request": null,
      "parameters": ["Origin9", "XCSRFToken10"]
    },
    {
      "path": "/invitation-access/password",
      "method": "post",
      "summary": "Create password account and accept",
      "public": true,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationJoined"},
      "request": {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "expectedRevision": {"type": "string", "pattern": "^(0|[1-9][0-9]*)$", "description": "Decimal string; never a JSON number."},
          "password": {"type": "string", "format": "password"}
        },
        "required": ["expectedRevision", "password"]
      },
      "parameters": ["Origin9", "XCSRFToken10", "DeviceId11"]
    },
    {
      "path": "/invitation-access/accept",
      "method": "post",
      "summary": "Accept with server-validated recipient evidence",
      "public": true,
      "status": "200",
      "response": {"$ref": "#/components/schemas/InvitationJoined"},
      "request": {
        "type": "object",
        "additionalProperties": false,
        "properties": {"expectedRevision": {"type": "string", "pattern": "^(0|[1-9][0-9]*)$", "description": "Decimal string; never a JSON number."}},
        "required": ["expectedRevision"]
      },
      "parameters": ["Origin9", "XCSRFToken10", "DeviceId11"]
    },
    {
      "path": "/invitation-access/saml",
      "method": "post",
      "summary": "Start SAML for the current invitation",
      "public": true,
      "status": "201",
      "response": {
        "type": "object",
        "additionalProperties": false,
        "properties": {"samlRequest": {"type": "string"}, "ssoUrl": {"type": "string", "format": "uri"}},
        "required": ["samlRequest", "ssoUrl"]
      },
      "request": null,
      "parameters": ["Origin9", "XCSRFToken10"]
    }
  ]
}"""
}
