package pl.iterators.epoca.utils.db

import org.specs2.mutable.Specification

class SchemaMigrationSpec extends Specification {
  "Configured application schema migrations" should {
    "preserve legacy history and isolate fresh schemas, application SQL and triggers" in {
      SchemaMigrationCheck.main(Array.empty[String])
      success
    }
  }
}
