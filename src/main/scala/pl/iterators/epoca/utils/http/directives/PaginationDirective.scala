package pl.iterators.epoca.utils.http.directives

import org.apache.pekko.http.scaladsl.server.{Directive, Directives, MalformedQueryParamRejection}
import pl.iterators.epoca.utils.db.PageSizeAndNumber

trait PaginationDirective extends Directives {
  def pagination: Directive[Tuple1[Option[PageSizeAndNumber]]] =
    parameters("pageSize".as[Int].?, "pageNumber".as[Int].?)
      .tfilter(_._1.forall(_ > 0), MalformedQueryParamRejection("pageSize", "pageSize should be > 0"))
      .tfilter(_._2.forall(_ > 0), MalformedQueryParamRejection("pageNumber", "pageNumber should be > 0"))
      .tmap { case (pageSize, pageNumber) =>
        PageSizeAndNumber.fromOptions(pageSize, pageNumber)
      }
}
