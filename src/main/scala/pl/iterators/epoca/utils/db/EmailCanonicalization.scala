package pl.iterators.epoca.utils.db

import pl.iterators.epoca.utils.db.BaseTable.driver.api._

/** One email key contract for JVM values, persisted identities and generated invitation keys. */
object EmailCanonicalization {
  // Supported email syntax folds ASCII only; legacy non-ASCII code points remain distinct.
  def canonical(value: String): String = value.trim.map { character =>
    if (character >= 'A' && character <= 'Z') (character + ('a' - 'A')).toChar else character
  }

  // String.trim removes U+0000..U+0020. PostgreSQL text cannot contain U+0000.
  // Keep the same explicit character set in V31/V32 in both migration lineages.
  private val trimCharactersSql = (1 to 32).map(code => f"\\$code%03o").mkString("E'", "", "'")
  private val prefix            = "translate(btrim("
  private val suffix            = s", $trimCharactersSql), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')"

  /** Only trusted SQL expressions (column names or parameter placeholders), never input values. */
  def sql(expression: String): String = prefix + expression + suffix

  def column(value: Rep[String]): Rep[String] = canonicalColumn(value)

  private val canonicalColumn = SimpleExpression.unary[String, String] { (argument, builder) =>
    val _ = builder.sqlBuilder += prefix
    builder.expr(argument)
    val _ = builder.sqlBuilder += suffix
  }
}
