package pl.iterators.epoca.saml.services

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.onelogin.saml2.util.Util
import org.apache.xml.security.signature.XMLSignature
import org.specs2.mutable.Specification
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.utils.Generators

import java.time.Instant
import scala.io.Source

class SamlUtilsServiceSpec extends Specification with Generators with CatsEffect {
  private def resource(name: String): String = {
    val source = Source.fromResource(s"saml/$name")
    try source.mkString
    finally source.close()
  }
  private lazy val key          = resource("synthetic-key.pem")
  private lazy val cert         = resource("synthetic-cert.pem")
  private lazy val organization = allGenerators[Organization].normal.generate.copy(slug = OrganizationSlug("signed-fixture"))
  private val issuer            = "https://idp.example.test/entity"
  private val requestId         = SamlRequestId("_ct208-request")
  private val baseUrl           = "https://app.example.test/"
  private val acs               = baseUrl + "organizations/signed-fixture/saml/acs"
  private val audience          = baseUrl + "organizations/signed-fixture/saml/metadata.xml"
  private lazy val samlConfig = allGenerators[SamlConfig].normal.generate.copy(
    organizationId = organization.id,
    idpCert = SamlIdpCert(cert),
    idpEntityId = SamlIdpEntityId(issuer),
    idpSsoUrl = SamlIdpSsoUrl("https://idp.example.test/sso"),
    isEnabled = SamlIsEnabled(true)
  )
  private lazy val sut = new SamlUtilsService(
    SamlUtilsService.Config(SamlSpKey(key), SamlSpCert(cert), "https://app.example.test/signin", "https://app.example.test/failure", baseUrl)
  )

  private def xml(responseIssuer: String = issuer, at: Instant = Instant.now()): String = {
    val before = at.minusSeconds(60)
    val after  = at.plusSeconds(300)
    s"""<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" ID="_response" Version="2.0" IssueInstant="$at" Destination="$acs" InResponseTo="$requestId">
       |<saml:Issuer>$responseIssuer</saml:Issuer>
       |<samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status>
       |<saml:Assertion ID="_assertion" Version="2.0" IssueInstant="$at">
       |<saml:Issuer>$responseIssuer</saml:Issuer>
       |<saml:Subject><saml:NameID Format="urn:oasis:names:tc:SAML:2.0:nameid-format:persistent">stable-subject</saml:NameID>
       |<saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer"><saml:SubjectConfirmationData InResponseTo="$requestId" Recipient="$acs" NotOnOrAfter="$after"/></saml:SubjectConfirmation></saml:Subject>
       |<saml:Conditions NotBefore="$before" NotOnOrAfter="$after"><saml:AudienceRestriction><saml:Audience>$audience</saml:Audience></saml:AudienceRestriction></saml:Conditions>
       |<saml:AuthnStatement AuthnInstant="$at" SessionIndex="_session"><saml:AuthnContext><saml:AuthnContextClassRef>urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport</saml:AuthnContextClassRef></saml:AuthnContext></saml:AuthnStatement>
       |<saml:AttributeStatement><saml:Attribute Name="email"><saml:AttributeValue>person@example.test</saml:AttributeValue></saml:Attribute></saml:AttributeStatement>
       |</saml:Assertion></samlp:Response>""".stripMargin
  }
  private def signed(value: String): String =
    Util.addSign(Util.convertStringToDocument(value), Util.loadPrivateKey(key), Util.loadCert(cert), XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256)
  private def valid(value: String, id: SamlRequestId = requestId): IO[Boolean] =
    sut.parseResponse(Util.base64encoder(value), samlConfig, organization).map(_.exists(sut.isValid(_, id)))

  private def context(value: String): IO[Option[pl.iterators.epoca.saml.domain.SamlAuthContext]] =
    sut.parseResponse(Util.base64encoder(signed(value)), samlConfig, organization).flatMap {
      case Some(response) if sut.isValid(response, requestId) => sut.getAuthContext(response)
      case _                                                  => IO.raiseError(new IllegalStateException("Synthetic signed fixture did not validate"))
    }

  "signed SAML responses" should {
    "validate an actual signature and expose its issuer and stable subject separately from the email attribute" in {
      sut.parseResponse(Util.base64encoder(signed(xml())), samlConfig, organization).flatMap { parsed =>
        val response  = parsed.get
        val validated = sut.isValid(response, requestId)
        sut.getAuthContext(response).map { context =>
          validated shouldEqual true
          context.map(_.issuer) shouldEqual Some(issuer)
          context.map(_.subject) shouldEqual Some("stable-subject")
          context.map(_.email.toString) shouldEqual Some("person@example.test")
        }
      }
    }
    "reject unsigned responses even when neither particular signature location is required" in {
      valid(xml()).map(_ shouldEqual false)
    }
    "reject a signature-invalid substituted subject" in {
      valid(signed(xml()).replace("stable-subject", "substituted-subject")).map(_ shouldEqual false)
    }
    "reject a signed response from a different issuer" in {
      valid(signed(xml(responseIssuer = "https://other.example.test/entity"))).map(_ shouldEqual false)
    }
    "reject a valid signature addressed to a different request" in {
      valid(signed(xml()), SamlRequestId("_other-request")).map(_ shouldEqual false)
    }
    "reject an expired signed assertion" in {
      valid(signed(xml(at = Instant.now().minusSeconds(3600)))).map(_ shouldEqual false)
    }
    "preserve legacy NameID email only when the email attribute is absent" in {
      val value = xml().replace("stable-subject", "legacy@example.test").replaceAll("(?s)<saml:AttributeStatement>.*?</saml:AttributeStatement>", "")
      context(value).map(_.map(_.email.toString) shouldEqual Some("legacy@example.test"))
    }
    "reject malformed explicit email instead of falling back to an email NameID" in {
      context(xml().replace("stable-subject", "fallback@example.test").replace("person@example.test", "not-an-email"))
        .map(_ shouldEqual None)
    }
    "reject ambiguous explicit email attributes" in {
      context(
        xml().replace(
          "person@example.test</saml:AttributeValue>",
          "person@example.test</saml:AttributeValue><saml:AttributeValue>other@example.test</saml:AttributeValue>"
        )
      )
        .map(_ shouldEqual None)
    }
    "reject a signed response substituted into another organization's endpoint" in {
      sut
        .parseResponse(Util.base64encoder(signed(xml())), samlConfig, organization.copy(slug = OrganizationSlug("different-organization")))
        .map(_.exists(sut.isValid(_, requestId)) shouldEqual false)
    }
    "reject malformed and missing request identifiers without failing the IO" in {
      for {
        malformed <- sut.extractRequestId("not-base64")
        absent    <- sut.extractRequestId(Util.base64encoder("<Response/>"))
      } yield (malformed, absent) shouldEqual (None, None)
    }
  }
}
