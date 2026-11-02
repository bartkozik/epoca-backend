package pl.iterators.epoca.oauth.services

import org.specs2.mutable.SpecificationLike
import pl.iterators.epoca.oauth.domain.OAuthDomain.OAuthProjectAccess.{AllProjects, SelectedProjects}
import pl.iterators.epoca.oauth.domain.OAuthDomain.{OAuthProjectAccessMode, OAuthProjectPolicyError}
import pl.iterators.epoca.project.domain.ProjectDomain.ProjectId

import java.util.UUID

class OAuthProjectPolicyServiceSpec extends SpecificationLike {
  private val p1 = ProjectId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
  private val p2 = ProjectId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
  private val p3 = ProjectId(UUID.fromString("33333333-3333-3333-3333-333333333333"))

  "OAuth project policy" should {
    "normalize AllProjects only when no project IDs are supplied" in {
      OAuthProjectPolicyService.normalize(OAuthProjectAccessMode.AllProjects, Set.empty) shouldEqual Right(AllProjects)
      OAuthProjectPolicyService.normalize(OAuthProjectAccessMode.AllProjects, Set(p1)) shouldEqual Left(
        OAuthProjectPolicyError.AllProjectsMustNotHaveProjectIds
      )
    }

    "require a nonempty normalized SelectedProjects set" in {
      OAuthProjectPolicyService.normalize(OAuthProjectAccessMode.SelectedProjects, Set.empty) shouldEqual Left(
        OAuthProjectPolicyError.SelectedProjectsMustNotBeEmpty
      )
      OAuthProjectPolicyService.normalize(OAuthProjectAccessMode.SelectedProjects, Set(p2, p1, p2)) shouldEqual Right(SelectedProjects(Set(p1, p2)))
    }

    "allow every project for AllProjects and only selected IDs otherwise" in {
      OAuthProjectPolicyService.allows(AllProjects, p3) should beTrue
      OAuthProjectPolicyService.allows(SelectedProjects(Set(p1, p2)), p2) should beTrue
      OAuthProjectPolicyService.allows(SelectedProjects(Set(p1, p2)), p3) should beFalse
    }

    "intersect candidates without any scope widening" in {
      OAuthProjectPolicyService.intersect(AllProjects, Set(p1, p3)) shouldEqual Set(p1, p3)
      OAuthProjectPolicyService.intersect(SelectedProjects(Set(p1, p2)), Set(p2, p3)) shouldEqual Set(p2)
      OAuthProjectPolicyService.intersect(SelectedProjects(Set(p1)), Set(p3)) should beEmpty
    }
  }
}
