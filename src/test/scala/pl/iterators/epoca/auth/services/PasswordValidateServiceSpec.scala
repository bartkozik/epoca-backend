package pl.iterators.epoca.auth.services

import com.softwaremill.macwire.wire
import org.specs2.specification.core.Fragment
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordPlain
import pl.iterators.epoca.auth.services.PasswordValidateService.PasswordValidateProblem._
import pl.iterators.epoca.auth.services.PasswordValidateService.PasswordValidateResult._
import pl.iterators.epoca.auth.services.PasswordValidateService.{Config, PasswordValidateResult}
import pl.iterators.epoca.utils.ServiceSpec

class PasswordValidateServiceSpec extends ServiceSpec {

  def testCase(password: String, result: PasswordValidateResult)(implicit config: PasswordValidateService.Config): Fragment =
    s"password: '$password', config: $config, expected result: $result" in {
      val service = wire[PasswordValidateService]
      service.validate(PasswordPlain(password)) shouldEqual result
    }

  "validate" should {
    val pEmpty               = ""
    val pLowLettersShort     = "abc"
    val pLowLetters          = "abcdefghijgsa"
    val pLowLettersLong      = "abcdefghijgsdsadadsaiohjqensdyhutgkwolqsdidfkjqweoidsauhwneodashuqwjdsa"
    val pDigits              = "314512353434"
    val pSpaces              = "          "
    val pWithoutSpecialChars = "Absd213213"
    val pFull                = "Absd213213!!! "

    val pConfigEmpty =
      Config(
        minimumLength = 0,
        maximumLength = None,
        includeDigit = false,
        includeLetter = false,
        includeLowerLetter = false,
        includeUpperLetter = false,
        includeSpecialChar = false
      )

    {
      implicit val c: Config = pConfigEmpty

      testCase(pEmpty, Valid)
      testCase(pLowLettersShort, Valid)
      testCase(pLowLetters, Valid)
      testCase(pLowLettersLong, Valid)
      testCase(pDigits, Valid)
      testCase(pSpaces, Valid)
      testCase(pWithoutSpecialChars, Valid)
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config = pConfigEmpty.copy(minimumLength = 8)

      testCase(pEmpty, Invalid(Set(InvalidLength)))
      testCase(pLowLettersShort, Invalid(Set(InvalidLength)))
      testCase(pLowLetters, Valid)
      testCase(pLowLettersLong, Valid)
      testCase(pDigits, Valid)
      testCase(pSpaces, Valid)
      testCase(pWithoutSpecialChars, Valid)
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config = pConfigEmpty.copy(minimumLength = 8, maximumLength = Some(20))

      testCase(pEmpty, Invalid(Set(InvalidLength)))
      testCase(pLowLettersShort, Invalid(Set(InvalidLength)))
      testCase(pLowLetters, Valid)
      testCase(pLowLettersLong, Invalid(Set(InvalidLength)))
      testCase(pDigits, Valid)
      testCase(pSpaces, Valid)
      testCase(pWithoutSpecialChars, Valid)
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config = pConfigEmpty.copy(includeDigit = true)

      testCase(pEmpty, Invalid(Set(NoDigit)))
      testCase(pLowLettersShort, Invalid(Set(NoDigit)))
      testCase(pLowLetters, Invalid(Set(NoDigit)))
      testCase(pLowLettersLong, Invalid(Set(NoDigit)))
      testCase(pDigits, Valid)
      testCase(pSpaces, Invalid(Set(NoDigit)))
      testCase(pWithoutSpecialChars, Valid)
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config = pConfigEmpty.copy(includeLetter = true)

      testCase(pEmpty, Invalid(Set(NoLetter)))
      testCase(pLowLettersShort, Valid)
      testCase(pLowLetters, Valid)
      testCase(pLowLettersLong, Valid)
      testCase(pDigits, Invalid(Set(NoLetter)))
      testCase(pSpaces, Invalid(Set(NoLetter)))
      testCase(pWithoutSpecialChars, Valid)
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config = pConfigEmpty.copy(includeLowerLetter = true)

      testCase(pEmpty, Invalid(Set(NoLowerLetter)))
      testCase(pLowLettersShort, Valid)
      testCase(pLowLetters, Valid)
      testCase(pLowLettersLong, Valid)
      testCase(pDigits, Invalid(Set(NoLowerLetter)))
      testCase(pSpaces, Invalid(Set(NoLowerLetter)))
      testCase(pWithoutSpecialChars, Valid)
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config = pConfigEmpty.copy(includeUpperLetter = true)

      testCase(pEmpty, Invalid(Set(NoUpperLetter)))
      testCase(pLowLettersShort, Invalid(Set(NoUpperLetter)))
      testCase(pLowLetters, Invalid(Set(NoUpperLetter)))
      testCase(pLowLettersLong, Invalid(Set(NoUpperLetter)))
      testCase(pDigits, Invalid(Set(NoUpperLetter)))
      testCase(pSpaces, Invalid(Set(NoUpperLetter)))
      testCase(pWithoutSpecialChars, Valid)
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config = pConfigEmpty.copy(includeSpecialChar = true)

      testCase(pEmpty, Invalid(Set(NoSpecialChar)))
      testCase(pLowLettersShort, Invalid(Set(NoSpecialChar)))
      testCase(pLowLetters, Invalid(Set(NoSpecialChar)))
      testCase(pLowLettersLong, Invalid(Set(NoSpecialChar)))
      testCase(pDigits, Invalid(Set(NoSpecialChar)))
      testCase(pSpaces, Invalid(Set(NoSpecialChar)))
      testCase(pWithoutSpecialChars, Invalid(Set(NoSpecialChar)))
      testCase(pFull, Valid)
    }

    {
      implicit val c: Config =
        Config(
          minimumLength = 8,
          maximumLength = Some(20),
          includeDigit = true,
          includeLetter = true,
          includeLowerLetter = true,
          includeUpperLetter = true,
          includeSpecialChar = true
        )

      testCase(pEmpty, Invalid(Set(InvalidLength, NoLetter, NoLowerLetter, NoUpperLetter, NoDigit, NoSpecialChar)))
      testCase(pLowLettersShort, Invalid(Set(InvalidLength, NoUpperLetter, NoDigit, NoSpecialChar)))
      testCase(pLowLetters, Invalid(Set(NoUpperLetter, NoDigit, NoSpecialChar)))
      testCase(pLowLettersShort, Invalid(Set(InvalidLength, NoUpperLetter, NoDigit, NoSpecialChar)))
      testCase(pDigits, Invalid(Set(NoLetter, NoLowerLetter, NoUpperLetter, NoSpecialChar)))
      testCase(pSpaces, Invalid(Set(NoLetter, NoLowerLetter, NoUpperLetter, NoDigit, NoSpecialChar)))
      testCase(pWithoutSpecialChars, Invalid(Set(NoSpecialChar)))
      testCase(pFull, Valid)
    }

  }

}
