package pl.iterators.kebs.scalacheck

import org.scalacheck.Arbitrary
import pl.iterators.epoca.testsupport.generators.{
  AllGenerators => ProjectAllGenerators,
  Generator => ProjectGenerator
}

trait Generator[T] {
  def ArbT: Arbitrary[T]

  def generate: T = ArbT.arbitrary.sample.get
}

trait AllGenerators[T] {
  val normal: Generator[T]
  val minimal: Generator[T]
  val maximal: Generator[T]
}

trait KebsScalacheckGenerators {
  implicit def baklava031AllGenerators[T](implicit projectGenerators: ProjectAllGenerators[T]): AllGenerators[T] =
    new AllGenerators[T] {
      private def adapt(projectGenerator: ProjectGenerator[T]): Generator[T] =
        new Generator[T] {
          override val ArbT: Arbitrary[T] = projectGenerator.ArbT
        }

      override val normal: Generator[T]  = adapt(projectGenerators.normal)
      override val minimal: Generator[T] = adapt(projectGenerators.minimal)
      override val maximal: Generator[T] = adapt(projectGenerators.maximal)
    }
}
