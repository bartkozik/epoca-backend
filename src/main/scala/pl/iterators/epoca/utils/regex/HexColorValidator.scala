package pl.iterators.epoca.utils.regex

import scala.util.matching.Regex

object HexColorValidator {
  private val hexColorRegex: Regex = """^#(?:[0-9a-fA-F]{3}){1,2}""".r

  def validate(color: String): Boolean =
    hexColorRegex.matches(color)
}
