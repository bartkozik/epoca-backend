package pl.iterators.epoca.auth.services

import com.google.firebase.auth.{FirebaseAuth, FirebaseToken}
import org.mockito.Mockito.verifyNoInteractions
import pl.iterators.epoca.auth.domain.AccountAuthentication.CredentialMethod
import pl.iterators.epoca.auth.routers.AuthRouter.LoginHttpRequestContext
import pl.iterators.epoca.auth.services.LoginByFirebaseService.{LoginByFirebaseRequest, LoginByFirebaseResult}
import pl.iterators.epoca.auth.services.LoginResolveService.{LoginResolution, LoginResolveRequest, UserLogin}
import pl.iterators.epoca.mail.domain.MailDomain.MailAddress
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository
import pl.iterators.epoca.organization.repositories.OrganizationUserRepository.OrganizationUserFilters
import pl.iterators.epoca.user.domain.UserDomain._
import pl.iterators.epoca.user.domain.{User, UserIdentity, UserWithIdentities}
import pl.iterators.epoca.user.repositories.UserRepository.UserFilters
import pl.iterators.epoca.user.repositories.{UserIdentityRepository, UserRepository}
import pl.iterators.epoca.user.services.UserWithIdentitiesFindService.UserWithIdentitiesFindResult
import pl.iterators.epoca.user.services.{UserCreateService, UserWithIdentitiesFindService}
import pl.iterators.epoca.utils.ServiceSpec
import pl.iterators.epoca.utils.json.JsonProtocol

class LoginByFirebaseServiceSpec extends ServiceSpec with JsonProtocol {
  trait TestCase extends FixedTimeBaseScope {
    val firebaseAuth: FirebaseAuth =
      org.mockito.Mockito.mock(classOf[FirebaseAuth], org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE))
    val firebaseAuthProvider: FirebaseAuthProvider                            = mock[FirebaseAuthProvider]
    val loginResolveService: LoginResolveService                              = mock[LoginResolveService]
    val userWithIdentitiesFindService: UserWithIdentitiesFindService          = mock[UserWithIdentitiesFindService]
    val organizationUserRepository: OrganizationUserRepository                = mock[OrganizationUserRepository]
    val userIdentityRepository: UserIdentityRepository                        = mock[UserIdentityRepository]
    val userRepository: UserRepository                                        = mock[UserRepository]
    val userCreateService: UserCreateService                                  = mock[UserCreateService]
    val avatarSyncService: pl.iterators.epoca.user.services.AvatarSyncService = mock[pl.iterators.epoca.user.services.AvatarSyncService]
    val service: LoginByFirebaseService                                       = wire[LoginByFirebaseService]
    val token: FirebaseToken =
      org.mockito.Mockito.mock(classOf[FirebaseToken], org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE))
    val user: User = allGenerators[User].normal.generate.copy(archivedAt = None)
    val identity: UserIdentity = allGenerators[UserIdentity].normal.generate.copy(
      userId = user.id,
      `type` = IdentityType.MailAddress,
      value = UserIdentityValue("firebase@example.test"),
      confirmed = UserIdentityConfirmed(true)
    )
    val requestContext: LoginHttpRequestContext = allGenerators[LoginHttpRequestContext].normal.generate
    val resolution: LoginResolution             = allGenerators[LoginResolution].normal.generate
    val request: LoginByFirebaseRequest         = LoginByFirebaseRequest("synthetic-token")
    when(firebaseAuthProvider.firebaseAuth).thenReturn(Some(firebaseAuth))
    when(firebaseAuth.verifyIdToken(request.firebaseToken)).thenReturn(token)
    when(token.getEmail).thenReturn(identity.value)
    when(userRepository.find(UserFilters(email = Some(MailAddress(identity.value))))).thenReturn(Some(user).asDBIO)
    when(userWithIdentitiesFindService.find(user.id)).thenReturn(UserWithIdentitiesFindResult.Ok(UserWithIdentities(user, Seq(identity))).asIO)
    when(organizationUserRepository.list(OrganizationUserFilters(userId = Some(user.id)))).thenReturn(Seq.empty.asDBIO)
    when(loginResolveService.resolve(any[LoginResolveRequest])).thenReturn(resolution.asIO)
  }
  "Firebase login" should {
    "mint Firebase evidence only after a verified email claim" in new TestCase {
      when(token.isEmailVerified).thenReturn(true)
      service.login(None, request, requestContext).asserting(_ shouldEqual LoginByFirebaseResult.Ok(resolution))
      verify(loginResolveService).resolve(
        UserLogin(requestContext, UserWithIdentities(user, Seq(identity)), Seq.empty, None, identity, Some(CredentialMethod.Firebase))
      )
    }
    "reject an unverified email before reading or changing any account" in new TestCase {
      when(token.isEmailVerified).thenReturn(false)
      service.login(None, request, requestContext).asserting(_ shouldEqual LoginByFirebaseResult.InvalidCredentials)
      verifyNoInteractions(
        userRepository,
        userWithIdentitiesFindService,
        userIdentityRepository,
        userCreateService,
        organizationUserRepository,
        loginResolveService
      )
    }
    "reject a verified token for a different account without calling the resolver" in new TestCase {
      when(token.isEmailVerified).thenReturn(true)
      service.login(Some(authUser), request, requestContext).asserting(_ shouldEqual LoginByFirebaseResult.InvalidCredentials)
      verifyNoInteractions(loginResolveService)
    }
    "reject failed token verification without resolving or looking up an account" in new TestCase {
      when(firebaseAuth.verifyIdToken(request.firebaseToken)).thenThrow(new IllegalArgumentException("invalid token"))
      service.login(None, request, requestContext).asserting(_ shouldEqual LoginByFirebaseResult.InvalidCredentials)
      verifyNoInteractions(loginResolveService, userRepository)
    }
  }
}
