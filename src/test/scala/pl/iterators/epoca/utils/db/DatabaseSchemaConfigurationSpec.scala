package pl.iterators.epoca.utils.db

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import pl.iterators.epoca.utils.quartz.QuartzProvider

class DatabaseSchemaConfigurationSpec extends Specification {
  private def config(url: String) = ConfigFactory.parseString(s"""db { url = "$url", user = "test", password = "test" }""")

  "Database schema configuration" should {
    "make the public default explicit for both Slick and Quartz" in {
      val configured = DatabaseSchemaConfiguration(config("jdbc:postgresql://localhost/epoca?sslmode=disable"))
      val expected   = "jdbc:postgresql://localhost/epoca?sslmode=disable&currentSchema=public"
      configured.getString("db.url") mustEqual expected
      QuartzProvider.Config(configured).dbUrl mustEqual expected
    }
    "preserve an explicit schema and other JDBC options" in {
      val url = "jdbc:postgresql://localhost/epoca?currentSchema=application_a&sslmode=require"
      DatabaseSchemaConfiguration(config(url)).getString("db.url") mustEqual url
    }
    "support the short PostgreSQL JDBC URL consistently" in {
      val url = "jdbc:postgresql:epoca?currentSchema=application_a"
      DatabaseSchemaConfiguration(config(url)).getString("db.url") mustEqual url
    }
    Seq("currentSchema=application_a&currentSchema=application_b", "current%53chema=application_a", "currentSchema=%61pplication_a").foreach {
      query =>
        s"reject ambiguous schema query '$query'" in {
          DatabaseSchemaConfiguration(config(s"jdbc:postgresql://localhost/epoca?$query")) must throwA[IllegalArgumentException]
        }
    }
    Seq("public,other", "pg_catalog", "information_schema", "quartz", "", "UpperCase").foreach { schema =>
      s"reject the invalid schema '$schema'" in {
        DatabaseSchemaConfiguration(config(s"jdbc:postgresql://localhost/epoca?currentSchema=$schema")) must throwA[IllegalArgumentException]
      }
    }
    "reject a non-PostgreSQL URL" in {
      DatabaseSchemaConfiguration(config("jdbc:h2:mem:test")) must throwA[IllegalArgumentException]
    }
  }
}
