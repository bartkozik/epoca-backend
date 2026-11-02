package pl.iterators.epoca.utils.job

import org.specs2.mutable.SpecificationLike

class TriggeredJobSpec extends SpecificationLike {
  private val codec = TriggeredJob.jsonBasedCodec[String]

  "jsonBasedCodec" should {
    "round-trip a valid JSON payload" in {
      codec.from(codec.to("payload")) shouldEqual "payload"
    }

    "preserve the external failure for missing or non-string JSON" in {
      codec.from(Map.empty) must throwA[RuntimeException](message = "Unable to get proper key from job map")
      codec.from(Map("json" -> 1)) must throwA[ClassCastException]
    }
  }
}
