package pl.iterators.epoca.testsupport.generators

import enumeratum.{ScalacheckInstances => EnumScalacheckInstances}
import org.scalacheck.{Arbitrary, Gen}
import pl.iterators.kebs.scalacheck.ScalacheckInstancesSupport

import scala.annotation.nowarn
import scala.reflect.ClassTag
import scala.reflect.macros.whitebox
import scala.util.Random

trait Generator[T] extends CommonArbitrarySupport {
  def ArbT: Arbitrary[T]
  final def generate: T = ArbT.arbitrary.sample.get
}

trait AllGenerators[T] {
  def normal: Generator[T]
  def minimal: Generator[T]
  def maximal: Generator[T]
}

@nowarn("cat=unused-params")
trait MinimalArbitrarySupport {
  implicit def emptyOption[T: Arbitrary]: Arbitrary[Option[T]] = Arbitrary(Gen.const(None))
  implicit def arbSeq[T: Arbitrary]: Arbitrary[Seq[T]]         = Arbitrary(Gen.const(Seq.empty[T]))
  implicit def emptyList[T: Arbitrary]: Arbitrary[List[T]]     = Arbitrary(Gen.const(List.empty[T]))
  implicit def emptySet[T: Arbitrary]: Arbitrary[Set[T]]       = Arbitrary(Gen.const(Set.empty[T]))
  implicit def emptyVector[T: Arbitrary]: Arbitrary[Vector[T]] = Arbitrary(Gen.const(Vector.empty[T]))
  implicit def emptyMap[K: Arbitrary, V: Arbitrary]: Arbitrary[Map[K, V]] = Arbitrary(Gen.const(Map.empty[K, V]))
  implicit def emptyArray[T: Arbitrary: ClassTag]: Arbitrary[Array[T]]     = Arbitrary(Gen.const(Array.empty[T]))
}

trait MaximalArbitrarySupport {
  implicit def someOption[T: Arbitrary]: Arbitrary[Option[T]] = Arbitrary(Gen.some(Arbitrary.arbitrary[T]))
  implicit def arbSeq[T: Arbitrary]: Arbitrary[Seq[T]] =
    Arbitrary(Gen.listOfN(1 + Random.nextInt(3), Arbitrary.arbitrary[T]))
  implicit def nonEmptyList[T: Arbitrary]: Arbitrary[List[T]] =
    Arbitrary(Gen.listOfN(1 + Random.nextInt(3), Arbitrary.arbitrary[T]))
  implicit def nonEmptySet[T: Arbitrary]: Arbitrary[Set[T]] =
    Arbitrary(Gen.listOfN(1 + Random.nextInt(3), Arbitrary.arbitrary[T]).map(_.toSet))
  implicit def nonEmptyVector[T: Arbitrary]: Arbitrary[Vector[T]] =
    Arbitrary(Gen.listOfN(1 + Random.nextInt(3), Arbitrary.arbitrary[T]).map(_.toVector))
  implicit def nonEmptyMap[K: Arbitrary, V: Arbitrary]: Arbitrary[Map[K, V]] =
    Arbitrary(Gen.mapOfN(1 + Random.nextInt(3), Arbitrary.arbitrary[(K, V)]))
  implicit def nonEmptyArray[T: Arbitrary: ClassTag]: Arbitrary[Array[T]] =
    Arbitrary(Gen.listOfN(1 + Random.nextInt(3), Arbitrary.arbitrary[T]).map(_.toArray))
}

trait CommonArbitrarySupport extends EnumScalacheckInstances with ScalacheckInstancesSupport

trait GeneratorVariants {
  implicit def allGenerators[T]: AllGenerators[T] = macro GeneratorVariantsMacro.materialize[T]
}

final class GeneratorVariantsMacro(val c: whitebox.Context) {
  import c.universe._

  def materialize[T: WeakTypeTag]: c.Expr[AllGenerators[T]] = {
    val target = weakTypeOf[T]
    c.Expr[AllGenerators[T]](q"""
      new _root_.pl.iterators.epoca.testsupport.generators.AllGenerators[$target] {
        private trait Creator extends _root_.pl.iterators.epoca.testsupport.generators.CommonArbitrarySupport {
          def create: _root_.pl.iterators.epoca.testsupport.generators.Generator[$target]
        }
        private object Normal extends Creator {
          def create = new _root_.pl.iterators.epoca.testsupport.generators.Generator[$target] {
            def ArbT = implicitly[_root_.org.scalacheck.Arbitrary[$target]]
          }
        }
        private object Minimal extends Creator with _root_.pl.iterators.epoca.testsupport.generators.MinimalArbitrarySupport {
          def create = new _root_.pl.iterators.epoca.testsupport.generators.Generator[$target] {
            def ArbT = implicitly[_root_.org.scalacheck.Arbitrary[$target]]
          }
        }
        private object Maximal extends Creator with _root_.pl.iterators.epoca.testsupport.generators.MaximalArbitrarySupport {
          def create = new _root_.pl.iterators.epoca.testsupport.generators.Generator[$target] {
            def ArbT = implicitly[_root_.org.scalacheck.Arbitrary[$target]]
          }
        }
        val normal = Normal.create
        val minimal = Minimal.create
        val maximal = Maximal.create
      }
    """)
  }
}
