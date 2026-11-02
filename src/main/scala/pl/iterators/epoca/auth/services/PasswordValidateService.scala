package pl.iterators.epoca.auth.services

import com.typesafe.config.{Config => TsConfig}
import enumeratum.{Enum, EnumEntry}
import pl.iterators.epoca.auth.domain.AuthDomain.PasswordPlain
import pl.iterators.epoca.auth.services.PasswordValidateService.{PasswordValidateProblem, PasswordValidateResult}

import scala.util.Try

class PasswordValidateService(config: PasswordValidateService.Config) {

  def validate(password: PasswordPlain): PasswordValidateResult = {
    val problems = validators.flatMap(v => v(password))

    if (problems.nonEmpty)
      PasswordValidateResult.Invalid(problems.toSet)
    else
      PasswordValidateResult.Valid
  }

  private val lengthValidator: PasswordPlain => Option[PasswordValidateProblem] = password =>
    Option.when(password.length < config.minimumLength || config.maximumLength.exists(_ < password.length))(PasswordValidateProblem.InvalidLength)

  private val includeDigitValidator: PasswordPlain => Option[PasswordValidateProblem] = password =>
    Option.when(config.includeDigit && !password.exists(_.isDigit))(PasswordValidateProblem.NoDigit)

  private val includeLetterValidator: PasswordPlain => Option[PasswordValidateProblem] = password =>
    Option.when(config.includeLetter && !password.exists(_.isLetter))(PasswordValidateProblem.NoLetter)

  private val includeLowerLetterValidator: PasswordPlain => Option[PasswordValidateProblem] = password =>
    Option.when(config.includeLowerLetter && !password.exists(c => c.isLetter && c.isLower))(PasswordValidateProblem.NoLowerLetter)

  private val includeUpperLetterValidator: PasswordPlain => Option[PasswordValidateProblem] = password =>
    Option.when(config.includeUpperLetter && !password.exists(c => c.isLetter && c.isUpper))(PasswordValidateProblem.NoUpperLetter)

  private val includeSpecialCharValidator: PasswordPlain => Option[PasswordValidateProblem] = password =>
    Option
      .when(config.includeSpecialChar && !password.exists(c => !c.isLetter && !c.isDigit && !c.isSpaceChar))(PasswordValidateProblem.NoSpecialChar)

  private val validators: Seq[PasswordPlain => Option[PasswordValidateProblem]] = Seq(
    lengthValidator,
    includeDigitValidator,
    includeLetterValidator,
    includeLowerLetterValidator,
    includeUpperLetterValidator,
    includeSpecialCharValidator
  )
}

object PasswordValidateService {

  case class Config(
    minimumLength: Int,
    maximumLength: Option[Int],
    includeDigit: Boolean,
    includeLetter: Boolean,
    includeLowerLetter: Boolean,
    includeUpperLetter: Boolean,
    includeSpecialChar: Boolean)
  object Config {
    def apply(tsConfig: TsConfig): Config = {
      val c = tsConfig.getConfig("user.password")
      Config(
        minimumLength = c.getInt("minimumLength"),
        maximumLength = Try(c.getInt("maximumLength")).toOption,
        includeDigit = c.getBoolean("includeDigit"),
        includeLetter = c.getBoolean("includeLetter"),
        includeLowerLetter = c.getBoolean("includeLowerLetter"),
        includeUpperLetter = c.getBoolean("includeUpperLetter"),
        includeSpecialChar = c.getBoolean("includeSpecialChar")
      )
    }
  }

  sealed trait PasswordValidateProblem extends EnumEntry
  object PasswordValidateProblem extends Enum[PasswordValidateProblem] {
    case object InvalidLength extends PasswordValidateProblem
    case object NoDigit       extends PasswordValidateProblem
    case object NoLetter      extends PasswordValidateProblem
    case object NoLowerLetter extends PasswordValidateProblem
    case object NoUpperLetter extends PasswordValidateProblem
    case object NoSpecialChar extends PasswordValidateProblem

    override def values: IndexedSeq[PasswordValidateProblem] = findValues
  }

  sealed trait PasswordValidateResult
  object PasswordValidateResult {
    final case object Valid                                          extends PasswordValidateResult
    final case class Invalid(problems: Set[PasswordValidateProblem]) extends PasswordValidateResult
  }

}
