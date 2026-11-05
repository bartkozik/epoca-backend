package pl.iterators.epoca.main

import org.flywaydb.core.Flyway

object MigrateMain {
  def main(args: Array[String]): Unit = {
    val _ = Flyway
      .configure()
      .dataSource(sys.env("DB_URL"), sys.env("DB_USER"), sys.env("DB_PASSWORD"))
      .schemas("public", "quartz")
      .defaultSchema("public")
      .locations("classpath:db/migration")
      .cleanDisabled(true)
      .load()
      .migrate()
  }
}
