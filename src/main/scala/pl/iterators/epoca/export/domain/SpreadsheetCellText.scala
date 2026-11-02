package pl.iterators.epoca.`export`.domain

private[domain] object SpreadsheetCellText {
  def neutralizeFormula(value: String): String =
    if (value.dropWhile(_ == ' ').headOption.exists(character => character == '=' || character == '+' || character == '-' || character == '@'))
      "'" + value
    else value
}
