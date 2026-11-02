package pl.iterators.epoca.utils

object UriLengthTestSupport {
  val FormerMaxUriLength: Int    = 2048
  val SupportedMaxUriLength: Int = 16 * 1024

  def withQuery(path: String, parameters: (String, Iterable[String])*): String = {
    val query = parameters.iterator
      .flatMap { case (name, values) => values.iterator.map(value => s"$name=$value") }
      .mkString("&")

    s"$path?$query"
  }
}
