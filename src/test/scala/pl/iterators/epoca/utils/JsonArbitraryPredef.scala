package pl.iterators.epoca.utils

import enumeratum.{Enum, EnumEntry}
import io.circe.{Json, JsonNumber, JsonObject}
import org.scalacheck.Gen._
import org.scalacheck.{Arbitrary, Gen}

import scala.collection.immutable
import scala.util.Random

private object JsonArbitraryPredef {
  private sealed trait JsonType extends EnumEntry
  private object JsonType extends Enum[JsonType] {
    override def values: immutable.IndexedSeq[JsonType] = findValues
    final case object Null   extends JsonType
    final case object Bool   extends JsonType
    final case object Number extends JsonType
    final case object String extends JsonType
    final case object Object extends JsonType
    final case object Array  extends JsonType
  }
}

trait JsonArbitraryPredef {
  import JsonArbitraryPredef.JsonType

  private def genNonEmptyString(): Gen[String] =
    nonEmptyListOf(alphaNumChar).map(chars => chars.mkString)

  private def scalingFactor = 5

  private def maxAggregateDepth: Int = 5
  private def maxAggregateSize: Int  = 5

  private def weightNull(depth: Int): Int      = if (depth == 0) 1 else 2
  private def weightPrimitive(depth: Int): Int = if (depth == 0) 3 else 15
  private def weightArray(depth: Int): Int     = if (depth == 0) 10 else if (depth > maxAggregateDepth) 0 else 10
  private def weightJson(depth: Int): Int      = if (depth == 0) 50 else if (depth > maxAggregateDepth) 0 else 10

  private def aggregateSize(depth: Int): Int = {
    val r = Math.min((scalingFactor * (1 / Math.log(1.0 + depth.toDouble))).toInt, maxAggregateSize)
    println(s"aggregateSize(depth = $depth) = $r")
    r
  }

  private implicit val arbitraryJsonNumber: Arbitrary[JsonNumber] = Arbitrary(
    Gen
      .oneOf(
        Arbitrary.arbitrary[Int].map(Json.fromInt(_).asNumber.get),
        Arbitrary.arbitrary[Long].map(Json.fromLong(_).asNumber.get),
        Arbitrary.arbitrary[Double].map(Json.fromDoubleOrString(_).asNumber.get),
        Arbitrary.arbitrary[Float].map(Json.fromFloatOrString(_).asNumber.get)
      )
  )

  private val genNull: Gen[Json]   = Gen.const(Json.Null)
  private val genBool: Gen[Json]   = Arbitrary.arbitrary[Boolean].map(Json.fromBoolean)
  private val genString: Gen[Json] = genNonEmptyString().map(Json.fromString)
  private val genNumber: Gen[Json] = Arbitrary.arbitrary[JsonNumber].map(Json.fromJsonNumber)

  private def randomNotNullJsonType: JsonType =
    Random.shuffle(JsonType.values.filterNot(_ == JsonType.Null)).head

  private def genFromJsonType(jsonType: JsonType, depth: Int): Gen[Json] =
    jsonType match {
      case JsonType.Null   => genNull
      case JsonType.Bool   => genBool
      case JsonType.Number => genNumber
      case JsonType.String => genString
      case JsonType.Object => genJsonObjectAsJson(depth)
      case JsonType.Array  => genJsonArrayAsJson(depth)
    }

  private def genKeyValuePair(depth: Int): Gen[(String, Json)] = for {
    key   <- genNonEmptyString()
    value <- genJson(1 + depth)
  } yield key -> value

  private def genJsonArrayAsJson(depth: Int): Gen[Json] =
    Gen.listOfN(aggregateSize(depth), genFromJsonType(randomNotNullJsonType, depth)).flatMap(res => Json.arr(res: _*))

  private def genJsonObject(depth: Int): Gen[JsonObject] =
    Gen.mapOfN(aggregateSize(depth), genKeyValuePair(1 + depth)).flatMap(res => JsonObject.fromMap(res))

  private def genJsonObjectAsJson(depth: Int): Gen[Json] =
    genJsonObject(depth).flatMap(res => Json.fromJsonObject(res))

  private def genJson(depth: Int): Gen[Json] = {
    val gens = List(
      (weightNull(depth), genNull),
      (weightPrimitive(depth), genBool),
      (weightPrimitive(depth), genNumber),
      (weightPrimitive(depth), genString),
      (weightArray(depth), genJsonArrayAsJson(depth)),
      (weightJson(depth), genJsonObjectAsJson(depth))
    )
    Gen.frequency(gens: _*)
  }

  implicit val arbJson: Arbitrary[Json]             = Arbitrary(genJson(0))
  implicit val arbJsonObject: Arbitrary[JsonObject] = Arbitrary(genJsonObject(0))
}
