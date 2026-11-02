package pl.iterators.epoca.auth.services

import pl.iterators.epoca.utils.ServiceSpec

class FirebaseAuthProviderSpec extends ServiceSpec {
  "FirebaseAuthProvider" should {
    "stay unconfigured when the service-account key is absent" in {
      val sut = new FirebaseAuthProvider(FirebaseAuthProvider.Config(None))

      sut.firebaseAuth should beNone
    }

    "reject a malformed service-account key without initializing Firebase" in {
      val sut = new FirebaseAuthProvider(FirebaseAuthProvider.Config(Some("not-json")))

      sut.firebaseAuth should beNone
    }
  }
}
