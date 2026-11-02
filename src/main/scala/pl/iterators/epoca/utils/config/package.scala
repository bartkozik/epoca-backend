package pl.iterators.epoca.utils

import pl.iterators.kebs.core.macros.{CaseClass1ToValueClass, ValueClassLike}
import pureconfig._

package object config extends CaseClass1ToValueClass {
  implicit def valueClassLikeReader[T, P](implicit valueClassLike: ValueClassLike[T, P], reader: ConfigReader[P]): ConfigReader[T] =
    reader.map(valueClassLike.apply)

  implicit def valueClassLikeWriter[T, P](implicit valueClassLike: ValueClassLike[T, P], writer: ConfigWriter[P]): ConfigWriter[T] =
    writer.contramap(valueClassLike.unapply)
}
