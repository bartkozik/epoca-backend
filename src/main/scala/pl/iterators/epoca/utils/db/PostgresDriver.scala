package pl.iterators.epoca.utils.db

import com.github.tminglei.slickpg._
import io.circe.Json
import io.circe.syntax._
import pl.iterators.epoca.auth.domain.AccountAuthentication
import pl.iterators.epoca.utils.json.JsonProtocol._
import pl.iterators.kebs.core.enums.EnumLike
import pl.iterators.kebs.core.macros.{CaseClass1ToValueClass, ValueClassLike}
import pl.iterators.kebs.enumeratum.KebsEnumeratum
import pl.iterators.kebs.slick.KebsSlickSupport
import pl.iterators.kebs.tagged._
import pl.iterators.kebs.tagged.slick.KebsTaggedSlickSupport

trait PostgresDriver
    extends ExPostgresProfile
    with PgCirceJsonSupport
    with PgDate2Support
    with PgArraySupport
    with PgHStoreSupport
    with PgNetSupport
    with KebsSlickSupport
    with KebsTaggedSlickSupport {
  override val pgjson = "jsonb"

  override protected def computeCapabilities: Set[_root_.slick.basic.Capability] =
    super.computeCapabilities + _root_.slick.jdbc.JdbcCapabilities.insertOrUpdate

  override val api = PostgresApi

  object PostgresApi
      extends ExtPostgresAPI
      with JsonImplicits
      with CirceJsonPlainImplicits
      with ArrayImplicits
      with Date2DateTimeImplicitsDuration
      with HStoreImplicits
      with NetImplicits
      with SimpleNetPlainImplicits
      with CaseClass1ToValueClass
      with KebsEnumeratum
      with KebsBasicImplicits
      with KebsValueClassLikeImplicits
      with KebsEnumImplicits
      with KebsTaggedImplicits {
    implicit val accountAuthenticationColumnType: BaseColumnType[AccountAuthentication] =
      MappedColumnType.base[AccountAuthentication, Json](_.asJson, _.as[AccountAuthentication].fold(throw _, identity))

    implicit def valueClassSeqColumnType[T, A](
      implicit
      valueClassLike: ValueClassLike[T, A],
      underlying: BaseColumnType[List[A]]
    ): BaseColumnType[Seq[T]] =
      MappedColumnType.base[Seq[T], List[A]](_.map(valueClassLike.unapply).toList, _.map(valueClassLike.apply))

    implicit def taggedSeqColumnType[T, U](implicit underlying: BaseColumnType[List[T]]): BaseColumnType[Seq[T @@ U]] =
      MappedColumnType.base[Seq[T @@ U], List[T]](_.map(value => value: T).toList, _.map(_.taggedWith[U]))

    implicit def enumSeqColumnType[E](implicit enumLike: EnumLike[E], underlying: BaseColumnType[List[String]]): BaseColumnType[Seq[E]] =
      MappedColumnType
        .base[Seq[E], List[String]](_.map(enumLike.getName).toList, _.map(enumLike.withName))(implicitly[scala.reflect.ClassTag[Seq[E]]], underlying)
  }
}

object PostgresDriver extends PostgresDriver
