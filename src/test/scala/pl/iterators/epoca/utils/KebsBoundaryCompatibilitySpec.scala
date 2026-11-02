package pl.iterators.epoca.utils

import _root_.json.Json
import io.circe.syntax._
import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.organization.domain.OrganizationDomain.{OrganizationId, OrganizationName, OrganizationUserRole}
import pl.iterators.epoca.utils.KebsBoundaryCompatibilitySpec._
import pl.iterators.epoca.utils.config._
import pl.iterators.epoca.utils.json.JsonProtocol
import pl.iterators.kebs.jsonschema.KebsJsonSchema
import pureconfig.ConfigSource

class KebsBoundaryCompatibilitySpec extends SpecificationLike with JsonProtocol with Generators with KebsJsonSchema {
  "Kebs compatibility boundaries" should {
    "preserve no-flat product JSON and enum casing" in {
      JsonEnvelope(
        wrapped = FlatValue("value"),
        role = OrganizationUserRole.Manager,
        id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000703")),
        name = OrganizationName("tagged-name"),
        optional = Some("present")
      ).asJson.noSpaces mustEqual
        """{"wrapped":{"value":"value"},"role":"Manager","id":"00000000-0000-0000-0000-000000000703","name":"tagged-name","optional":"present"}"""
    }

    "decode a fixed no-flat product literal" in {
      io.circe.parser.decode[JsonEnvelope](
        """{"wrapped":{"value":"value"},"role":"Manager","id":"00000000-0000-0000-0000-000000000703","name":"tagged-name","optional":"present"}"""
      ) must beRight(
        JsonEnvelope(
          wrapped = FlatValue("value"),
          role = OrganizationUserRole.Manager,
          id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000703")),
          name = OrganizationName("tagged-name"),
          optional = Some("present")
        )
      )
    }

    "encode None as its fixed accepted representation" in {
      JsonEnvelope(
        wrapped = FlatValue("value"),
        role = OrganizationUserRole.Manager,
        id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000703")),
        name = OrganizationName("tagged-name"),
        optional = None
      ).asJson.noSpaces mustEqual
        """{"wrapped":{"value":"value"},"role":"Manager","id":"00000000-0000-0000-0000-000000000703","name":"tagged-name","optional":null}"""
    }

    "decode None from its fixed accepted representation" in {
      io.circe.parser.decode[JsonEnvelope](
        """{"wrapped":{"value":"value"},"role":"Manager","id":"00000000-0000-0000-0000-000000000703","name":"tagged-name","optional":null}"""
      ) must beRight(
        JsonEnvelope(
          wrapped = FlatValue("value"),
          role = OrganizationUserRole.Manager,
          id = OrganizationId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000703")),
          name = OrganizationName("tagged-name"),
          optional = None
        )
      )
    }

    "preserve one-field PureConfig readers and writers" in {
      ConfigSource.string("value = 37").at("value").load[ConfigValue] must beRight(ConfigValue(37))
      pureconfig.ConfigWriter[ConfigValue].to(ConfigValue(37)).unwrapped() mustEqual Int.box(37)
    }

    "preserve minimal and maximal generator semantics" in {
      val variants = allGenerators[GeneratorProbe]
      val minimal  = variants.minimal.generate
      val maximal  = variants.maximal.generate
      minimal.optional must beNone
      minimal.values must beEmpty
      maximal.optional must beSome
      maximal.values must not(beEmpty)
    }

    "preserve a one-field wrapper as the underlying JSON Schema type" in {
      Json.schema[FlatValue] mustEqual _root_.json.Schema.`string`[FlatValue]
    }
  }
}

object KebsBoundaryCompatibilitySpec {
  final case class FlatValue(value: String)
  final case class JsonEnvelope(
    wrapped: FlatValue,
    role: OrganizationUserRole,
    id: OrganizationId,
    name: OrganizationName,
    optional: Option[String])
  final case class ConfigValue(value: Int)
  final case class GeneratorProbe(
    required: String,
    optional: Option[String],
    values: Seq[Int])
}
