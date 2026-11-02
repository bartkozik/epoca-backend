package pl.iterators.epoca.saml.repositories

import pl.iterators.epoca.organization.repositories.fixtures.OrganizationFixture
import pl.iterators.epoca.saml.domain.SamlConfig
import pl.iterators.epoca.saml.domain.SamlDomain._
import pl.iterators.epoca.testsupport.generators.Generator
import pl.iterators.epoca.user.repositories.fixtures.UserFixture
import pl.iterators.epoca.utils.PgRepositorySpec
import pl.iterators.epoca.utils.db.BaseTable.driver.api._

import java.util.UUID

class SamlConfigRepositoryImplSpec extends PgRepositorySpec with OrganizationFixture with UserFixture {
  private val rep: SamlConfigRepository = new SamlConfigRepositoryImpl()

  val samlConfigGenerator: Generator[SamlConfig] = allGenerators[SamlConfig].normal

  "trust epoch" should {
    "rotate issuer certificate and SSO URL but retain trust for language and initialization changes" in withDatabase {
      for {
        org     <- withOrganization()
        initial <- rep.upsert(samlConfigGenerator.generate.copy(organizationId = org.id))
        first   <- sql"SELECT trust_epoch FROM saml_config WHERE organization_id=${org.id.toString}::uuid".as[Long].head
        issuer  <- rep.upsert(initial.copy(idpEntityId = SamlIdpEntityId("changed-issuer")))
        second  <- sql"SELECT trust_epoch FROM saml_config WHERE organization_id=${org.id.toString}::uuid".as[Long].head
        cert    <- rep.upsert(issuer.copy(idpCert = SamlIdpCert("changed-cert")))
        third   <- sql"SELECT trust_epoch FROM saml_config WHERE organization_id=${org.id.toString}::uuid".as[Long].head
        url     <- rep.upsert(cert.copy(idpSsoUrl = SamlIdpSsoUrl("https://changed.example.test/sso")))
        fourth  <- sql"SELECT trust_epoch FROM saml_config WHERE organization_id=${org.id.toString}::uuid".as[Long].head
        _       <- rep.upsert(url.copy(langCode = SamlLangCode("pl"), allowAccountInit = SamlAllowAccountInitialization(!url.allowAccountInit)))
        fifth   <- sql"SELECT trust_epoch FROM saml_config WHERE organization_id=${org.id.toString}::uuid".as[Long].head
      } yield Vector(first, second, third, fourth, fifth) mustEqual Vector(1L, 2L, 3L, 4L, 4L)
    }
    "recreate beyond historical binding and request epochs instead of inheriting their trust" in withDatabase {
      for {
        org    <- withOrganization()
        user   <- withUser()
        config <- rep.upsert(samlConfigGenerator.generate.copy(organizationId = org.id))
        _ <-
          sqlu"INSERT INTO saml_identity_binding(organization_id,trust_epoch,issuer,subject,user_id,canonical_email,verified_at,proof) VALUES(${org.id.toString}::uuid,7,'old-issuer','old-subject',${user.id.toString}::uuid,'old@example.test',now(),'{}'::jsonb)"
        requestId = s"epoch-${UUID.randomUUID()}"
        _ <-
          sqlu"INSERT INTO saml_request(id,used,created_at,organization_id,trust_epoch,purpose,browser_nonce_hash) VALUES($requestId,false,now(),${org.id.toString}::uuid,11,'Login',decode(repeat('00',32),'hex'))"
        _     <- sqlu"DELETE FROM saml_config WHERE organization_id=${org.id.toString}::uuid"
        _     <- rep.upsert(config)
        epoch <- sql"SELECT trust_epoch FROM saml_config WHERE organization_id=${org.id.toString}::uuid".as[Long].head
      } yield epoch mustEqual 12L
    }
  }

  "CRUD" should {
    "work as follows" in withDatabase {
      for {
        org1 <- withOrganization()
        org2 <- withOrganization()
        org3 <- withOrganization()
        org4 <- withOrganization()

        r1 <- rep.upsert(samlConfigGenerator.generate.copy(organizationId = org1.id))
        r2 <- rep.upsert(samlConfigGenerator.generate.copy(organizationId = org2.id))
        r3 <- rep.upsert(samlConfigGenerator.generate.copy(organizationId = org3.id))

        f1 <- rep.find(org1.id)
        f2 <- rep.find(org2.id)
        f3 <- rep.find(org3.id)
        f4 <- rep.find(org4.id)

        ru1 = r1.copy(idpCert = samlConfigGenerator.generate.idpCert)
        ru2 = r2.copy(idpCert = samlConfigGenerator.generate.idpCert)

        _ <- rep.upsert(ru1)
        _ <- rep.upsert(ru2)

        fu1 <- rep.find(org1.id)
        fu2 <- rep.find(org2.id)
        fu3 <- rep.find(org3.id)
        fu4 <- rep.find(org4.id)
      } yield {
        f1 shouldEqual Some(r1)
        f2 shouldEqual Some(r2)
        f3 shouldEqual Some(r3)
        f4 shouldEqual None

        fu1 shouldEqual Some(ru1)
        fu2 shouldEqual Some(ru2)
        fu3 shouldEqual Some(r3)
        fu4 shouldEqual None
      }
    }
  }
}
