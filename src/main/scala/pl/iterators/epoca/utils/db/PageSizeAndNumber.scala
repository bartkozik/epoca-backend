package pl.iterators.epoca.utils.db

case class PageSizeAndNumber(size: Int, number: Int) {
  def offset: Int = size * (number - 1)
}

object PageSizeAndNumber {
  def fromOptions(size: Option[Int], number: Option[Int]): Option[PageSizeAndNumber] =
    (size, number) match {
      case (Some(size), Some(number)) => Some(PageSizeAndNumber(size = size, number = number))
      case _                          => None
    }
}

final case class PaginatedResult[T](count: Int, results: Seq[T])
