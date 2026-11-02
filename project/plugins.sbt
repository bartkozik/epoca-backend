addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

addSbtPlugin("com.github.sbt" % "flyway-sbt" % "12.0.0")

libraryDependencies += "org.flywaydb" % "flyway-database-postgresql" % "12.0.0"

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

addSbtPlugin("com.timushev.sbt" % "sbt-rewarn" % "0.1.3")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.4")

addSbtPlugin("pl.iterators" % "baklava-sbt-plugin" % "0.3.1")

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
