package pl.iterators.epoca.saml.services

import cats.effect.IO
import com.onelogin.saml2.authn.{AuthnRequest, SamlResponse}
import com.onelogin.saml2.settings.{Saml2Settings, SettingsBuilder}
import com.onelogin.saml2.util.Util
import com.typesafe.config.{Config => TsConfig}
import org.apache.xml.security.signature.XMLSignature
import pl.iterators.epoca.organization.domain.Organization
import pl.iterators.epoca.organization.domain.OrganizationDomain.OrganizationSlug
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.saml.domain.{SamlAuthContext, SamlConfig}
import pl.iterators.epoca.saml.services.SamlUtilsService.Config
import pl.iterators.epoca.user.domain.UserDomain.{IdentityType, UserIdentityValue}
import pl.iterators.epoca.utils.db.EmailCanonicalization
import pl.iterators.epoca.utils.log.LoggingSupport

import java.nio.charset.StandardCharsets
import java.util
import scala.jdk.CollectionConverters._
import scala.util.Try

class SamlUtilsService(config: Config) extends LoggingSupport {
  def isValid(response: SamlResponse, requestId: SamlRequestId): Boolean = response.isValid(requestId)

  def parseResponse(
    responseRep: String,
    samlConfig: SamlConfig,
    organization: Organization
  ): IO[Option[SamlResponse]] =
    settings(samlConfig, organization).map(saml2Settings =>
      Try {
        new SamlResponse(saml2Settings, urlBuilder(organization.slug, "acs"), responseRep)
      }.toOption
    )

  def extractRequestId(responseRep: String): IO[Option[SamlRequestId]] = IO {
    Try {
      val xml      = new String(Util.base64decoder(responseRep), StandardCharsets.UTF_8)
      val document = Util.loadXML(xml)
      val id       = document.getDocumentElement.getAttribute("InResponseTo")
      require(id.nonEmpty, "Missing request identifier")
      SamlRequestId(id)
    }.toOption
  }

  /** Caller first validates the response against the persisted request using isValid. */
  def getAuthContext(response: SamlResponse): IO[Option[SamlAuthContext]] = IO {
    Try {
      val issuer  = response.getAssertionIssuer
      val subject = response.getNameId
      require(issuer != null && issuer.trim.nonEmpty && subject != null && subject.trim.nonEmpty, "Missing SAML identity")
      require(Option(response.getResponseIssuer).forall(_ == issuer), "Inconsistent SAML issuers")
      val attributes = response.getAttributes.asScala.toMap.map { case (key, values) => key -> values.asScala.toList }
      val email = attributes.get("email") match {
        case None              => subject
        case Some(List(value)) => value
        case _                 => throw new IllegalArgumentException("Ambiguous SAML email")
      }
      val canonical = EmailCanonicalization.canonical(email)
      require(UserIdentityValue.isValid(IdentityType.MailAddress, UserIdentityValue(canonical)), "Invalid SAML email")
      SamlAuthContext(subject, attributes.updated("email", List(canonical)), issuer)
    }.toOption
  }

  def settings(samlConfig: SamlConfig, organization: Organization): IO[Saml2Settings] = IO {
    val samlData = new util.HashMap[String, Object]()
    samlData.put("onelogin.saml2.strict", true.asInstanceOf[Object])
    samlData.put("onelogin.saml2.debug", false.asInstanceOf[Object])
    samlData.put("onelogin.saml2.sp.entityid", urlBuilder(organization.slug, "metadata.xml"))
    samlData.put("onelogin.saml2.sp.assertion_consumer_service.url", urlBuilder(organization.slug, "acs"))
    samlData.put("onelogin.saml2.sp.x509cert", config.spCert)
    samlData.put("onelogin.saml2.security.want_xml_validation", true.asInstanceOf[Object])
    samlData.put("onelogin.saml2.security.authnrequest_signed", true.asInstanceOf[Object])
    samlData.put("onelogin.saml2.security.want_messages_signed", false.asInstanceOf[Object])
    samlData.put("onelogin.saml2.security.want_assertions_signed", false.asInstanceOf[Object])
    samlData.put("onelogin.saml2.security.want_assertions_encrypted", false.asInstanceOf[Object])
    samlData.put("onelogin.saml2.security.want_nameid_encrypted", false.asInstanceOf[Object])
    samlData.put("onelogin.saml2.sp.privatekey", config.spKey)
    samlData.put("onelogin.saml2.idp.entityid", samlConfig.idpEntityId)
    samlData.put("onelogin.saml2.idp.single_sign_on_service.url", samlConfig.idpSsoUrl)
    samlData.put("onelogin.saml2.idp.x509cert", samlConfig.idpCert)
    samlData.put("onelogin.saml2.organization.name", organization.name)
    samlData.put("onelogin.saml2.organization.displayname", organization.slug)
    samlData.put("onelogin.saml2.organization.lang", samlConfig.langCode)
    samlData.put("onelogin.saml2.contacts.technical.given_name", "Lukasz Sowa")
    samlData.put("onelogin.saml2.contacts.technical.email_address", "user-680f70297697@example.test")
    samlData.put("onelogin.saml2.contacts.support.given_name", "Lukasz Sowa")
    samlData.put("onelogin.saml2.contacts.support.email_address", "user-680f70297697@example.test")
    samlData.put("onelogin.saml2.unique_id_prefix", "EPOCA_")
    val builder = new SettingsBuilder()
    builder.fromValues(samlData).build
  }

  private def urlBuilder(organizationSlug: OrganizationSlug, path: String): String =
    config.deployedUrl + "organizations/" + organizationSlug + "/saml/" + path

  def buildAndEncodeRequest(authnRequest: AuthnRequest): IO[String] =
    for {
      key           <- IO(Util.loadPrivateKey(config.spKey))
      cert          <- IO(Util.loadCert(config.spCert))
      requestXml    <- IO(Util.convertStringToDocument(authnRequest.getAuthnRequestXml))
      requestString <- IO(Util.addSign(requestXml, key, cert, XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256))
      encoded       <- IO(Util.base64encoder(requestString))
    } yield encoded

  def getAuthnRequest(samlConfig: SamlConfig, organization: Organization): IO[AuthnRequest] =
    settings(samlConfig, organization).map(new AuthnRequest(_))

}

object SamlUtilsService {
  case class Config(
    spKey: SamlSpKey,
    spCert: SamlSpCert,
    setTokenUrl: String,
    failLoginUrl: String,
    deployedUrl: String)
  object Config {
    def apply(config: TsConfig): Config = {
      val spKey        = config.getString("saml.spKey")
      val spCert       = config.getString("saml.spX509cert")
      val setTokenUrl  = config.getString("saml.setTokenUrl")
      val deployedUrl  = config.getString("http.deployedUrl")
      val failLoginUrl = config.getString("saml.failLoginUrl")
      Config(spKey = SamlSpKey(spKey), spCert = SamlSpCert(spCert), deployedUrl = deployedUrl, setTokenUrl = setTokenUrl, failLoginUrl = failLoginUrl)
    }
  }
}
