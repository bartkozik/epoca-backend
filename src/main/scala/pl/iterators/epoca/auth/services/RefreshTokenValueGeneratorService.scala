package pl.iterators.epoca.auth.services

import pl.iterators.epoca.auth.domain.AuthDomain.RefreshTokenValue

import java.math.BigInteger
import java.security.SecureRandom

class RefreshTokenValueGeneratorService {

  def generate: RefreshTokenValue = RefreshTokenValue(new BigInteger(130, new SecureRandom()).toString(32))
}
