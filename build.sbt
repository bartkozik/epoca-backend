name := "epoca-backend"
organization := "pl.iterators"

version := "0.1-SNAPSHOT"

scalaVersion := "2.13.18"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

scalacOptions ++= Seq(
  "-release:17",
  "-Ymacro-annotations",
  "-Ywarn-macros:after",
  "-deprecation",
  "-encoding",
  "utf-8",
  "-explaintypes",
  "-feature",
  "-language:existentials",
  "-language:experimental.macros",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Xcheckinit",
  "-Xlint:constant",
  "-Xlint:delayedinit-select",
  "-Xlint:doc-detached",
  "-Xlint:inaccessible",
  "-Xlint:infer-any",
  "-Xlint:missing-interpolator",
  "-Xlint:nullary-unit",
  "-Xlint:option-implicit",
  "-Xlint:package-object-classes",
  "-Xlint:poly-implicit-overload",
  "-Xlint:private-shadow",
  "-Xlint:stars-align",
  "-Xlint:type-parameter-shadow",
  "-Ywarn-extra-implicit",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:imports",
  "-Ywarn-unused:locals",
  "-Ywarn-unused:params",
  "-Ywarn-unused:patvars",
  "-Ywarn-unused:privates",
  "-Ywarn-value-discard",
  "-Wnonunit-statement",
  "-Wnonunit-if",
  if (insideCI.value || !sys.env.get("SBT_NO_FATAL_WARNING").contains("true")) "-Wconf:any:error" else "-Wconf:any:warning"
)

Test / scalacOptions --= Seq("-Wnonunit-statement", "-Wnonunit-if")

// Integration specs share one disposable PostgreSQL schema and must not mutate it concurrently.
Test / parallelExecution := false
Test / testListeners ++= (if (sys.env.get("CI").contains("true")) Seq(CiTestProgress) else Seq.empty)

libraryDependencies ++= {
  val pekkoHttpV             = "1.4.0"
  val pekkoHttpJsonV         = "3.12.0"
  val pekkoV                 = "1.7.0"
  val awsSdkV                = "2.23.19"
  val awsCrtV                = "0.29.9"
  val pureconfigV            = "0.17.10"
  val sttpV                  = "4.0.0-M2"
  val catsV                  = "2.13.0"
  val catsEffectV            = "3.5.7"
  val catsEffectTestingV     = "1.5.0"
  val chimneyV               = "1.11.0"
  val kamonV                 = "2.7.1"
  val firebaseV              = "9.10.0"
  val flywayV                = "12.0.0"
  val kebsV                  = "2.1.6"
  val logbackV               = "1.4.14"
  val macwireV               = "2.6.7"
  val postgresqlV            = "42.7.13"
  val slickV                 = "3.6.1"
  val slickPgV               = "0.23.1"
  val mockitoV               = "1.17.45"
  val specs2V                = "4.23.0"
  val sealedV                = "1.3.0"
  val enumeratumV            = "1.9.8"
  val enumeratumScalacheckV  = "1.7.4"
  val baklavaV               = "0.3.1"
  val jwtCoreV               = "11.0.4"
  val jacksonCoreV           = "2.19.1"
  val jbcryptV               = "0.4"
  val typesafeSslConfigCoreV = "0.6.1"
  val quartzV                = "2.3.2"
  val javaSamlV              = "2.9.0"
  val spoiwoV                = "2.2.1"
  Seq(
    "ch.qos.logback"                 % "logback-classic"            % logbackV,
    "com.softwaremill.macwire"      %% "macros"                     % macwireV              % "provided",
    "com.softwaremill.macwire"      %% "proxy"                      % macwireV,
    "com.softwaremill.macwire"      %% "util"                       % macwireV,
    "com.beachape"                  %% "enumeratum"                 % enumeratumV,
    "com.beachape"                  %% "enumeratum-scalacheck"      % enumeratumScalacheckV % "test",
    "org.apache.pekko"              %% "pekko-http"                 % pekkoHttpV,
    "org.apache.pekko"              %% "pekko-http-testkit"         % pekkoHttpV            % "test",
    "org.apache.pekko"              %% "pekko-slf4j"                % pekkoV,
    "org.apache.pekko"              %% "pekko-stream"               % pekkoV,
    "com.github.pureconfig"         %% "pureconfig"                 % pureconfigV,
    "com.softwaremill.sttp.client4" %% "fs2"                        % sttpV,
    "com.softwaremill.sttp.client4" %% "circe"                      % sttpV,
    "com.softwaremill.sttp.client4" %% "slf4j-backend"              % sttpV,
    "software.amazon.awssdk"         % "s3"                         % awsSdkV,
    ("org.quartz-scheduler"          % "quartz"                     % quartzV).exclude("com.zaxxer", "HikariCP-java7"),
    "org.flywaydb"                   % "flyway-core"                % flywayV,
    "org.flywaydb"                   % "flyway-database-postgresql" % flywayV,
    "com.typesafe"                  %% "ssl-config-core"            % typesafeSslConfigCoreV,
    "com.github.pjfanning"          %% "pekko-http-circe"           % pekkoHttpJsonV,
    "org.postgresql"                 % "postgresql"                 % postgresqlV,
    "com.typesafe.slick"            %% "slick"                      % slickV,
    "com.typesafe.slick"            %% "slick-hikaricp"             % slickV,
    "com.github.tminglei"           %% "slick-pg"                   % slickPgV,
    "com.github.tminglei"           %% "slick-pg_circe-json"        % slickPgV,
    "io.scalaland"                  %% "chimney"                    % chimneyV,
    "io.kamon"                      %% "kamon-datadog"              % kamonV,
    "io.kamon"                      %% "kamon-bundle"               % kamonV,
    "org.typelevel"                 %% "cats-core"                  % catsV,
    "org.typelevel"                 %% "cats-effect"                % catsEffectV,
    "org.typelevel"                 %% "cats-effect-testkit"        % catsEffectV           % "test",
    "org.typelevel"                 %% "cats-effect-testing-specs2" % catsEffectTestingV    % "test",
    "pl.iterators"                  %% "kebs-pekko-http"            % kebsV,
    "pl.iterators"                  %% "kebs-circe"                 % kebsV,
    "pl.iterators"                  %% "kebs-tagged"                % kebsV,
    "pl.iterators"                  %% "kebs-tagged-meta"           % kebsV,
    "pl.iterators"                  %% "kebs-slick"                 % kebsV,
    "pl.iterators"                  %% "kebs-instances"             % kebsV,
    "pl.iterators"                  %% "kebs-scalacheck"            % kebsV                 % "test",
    "pl.iterators"                  %% "kebs-jsonschema"            % kebsV                 % "test",
    "pl.iterators"                  %% "sealed-monad"               % sealedV,
    "com.github.jwt-scala"          %% "jwt-core"                   % jwtCoreV,
    "org.mindrot"                    % "jbcrypt"                    % jbcryptV,
    "com.google.firebase"            % "firebase-admin"             % firebaseV,
    "org.apache.pekko"              %% "pekko-stream-testkit"       % pekkoV                % "test",
    "org.apache.pekko"              %% "pekko-testkit"              % pekkoV                % "test",
    "org.mockito"                   %% "mockito-scala"              % mockitoV              % "test",
    "org.specs2"                    %% "specs2-core"                % specs2V               % "test",
    "pl.iterators"                  %% "baklava-pekko-http-routes"  % baklavaV,
    "pl.iterators"                  %% "baklava-pekko-http"         % baklavaV              % "test",
    "pl.iterators"                  %% "baklava-circe"              % baklavaV              % "test",
    "pl.iterators"                  %% "baklava-formatter-openapi"  % baklavaV              % "test",
    "pl.iterators"                  %% "baklava-generator"          % baklavaV              % "test",
    "pl.iterators"                  %% "baklava-specs2"             % baklavaV              % "test",
    "com.fasterxml.jackson.core"     % "jackson-core"               % jacksonCoreV,
    "com.onelogin"                   % "java-saml-core"             % javaSamlV,
    "com.onelogin"                   % "java-saml-toolkit"          % javaSamlV,
    "com.norbitltd"                 %% "spoiwo"                     % spoiwoV
  )
}

enablePlugins(JavaServerAppPackaging)
dockerRepository := sys.env.get("DOCKER_REPO")
packageName := "epoca-backend"
lazy val requireRuntimeImage = taskKey[String]("Fails unless EPOCA_RUNTIME_IMAGE names the Docker base image")
requireRuntimeImage := sys.env
  .get("EPOCA_RUNTIME_IMAGE")
  .filter(_.trim.nonEmpty)
  .getOrElse(sys.error("EPOCA_RUNTIME_IMAGE is required for Docker packaging"))
dockerBaseImage := sys.env.getOrElse("EPOCA_RUNTIME_IMAGE", "EPOCA_RUNTIME_IMAGE-is-unset")
Docker / stage := (Docker / stage).dependsOn(requireRuntimeImage).value
Docker / daemonUser := "root"
dockerUpdateLatest := true

import com.typesafe.sbt.packager.docker.Cmd
dockerCommands += Cmd("ARG", "BUILD_VERSION")
dockerCommands += Cmd("ENV", "APP_VERSION=$BUILD_VERSION")
dockerCommands += Cmd("ADD", "--chown=root:root", "baklava", "/baklava")
dockerEnvVars += ("BAKLAVA_ROUTES_FILESYSTEM_PATH", "/baklava")
dockerCommands += Cmd("ARG", "PORT=80")
dockerCommands += Cmd("ENV", "PORT ${PORT}")
dockerCommands += Cmd("USER", "root")
dockerCommands += Cmd("RUN", "chmod 755", "/opt/docker/bin/epoca-backend")
dockerCommands += Cmd("ADD", "https://github.com/moparisthebest/static-curl/releases/latest/download/curl-amd64", "/bin/curl")
dockerCommands += Cmd("RUN", "chmod 755", "/bin/curl")
dockerCommands += Cmd(
  "RUN",
  "groupadd --gid 1001 epoca && useradd --uid 1001 --gid 1001 --create-home --home-dir /home/epoca --shell /usr/sbin/nologin epoca"
)
dockerCommands += Cmd("USER", "epoca")
dockerCommands += Cmd(
  "HEALTHCHECK",
  "--interval=10s",
  "--timeout=3s",
  "--start-period=60s",
  "--retries=2",
  "CMD curl http://127.0.0.1:$PORT/" +
    s"${sys.env.getOrElse("API_PREFIX", "api")}/" +
    "v1/" +
    s"health-check || exit 1"
)

enablePlugins(FlywayPlugin)
PostgresMigrations.settings

inConfig(Test)(PostgresMigrations.testSettings)

lazy val KebsTestCompat = config("kebsTestCompat").extend(Test)
configs(KebsTestCompat)
inConfig(KebsTestCompat)(Defaults.configSettings ++ Defaults.compileSettings)
KebsTestCompat / scalaSource := baseDirectory.value / "src" / "kebs-test-compat" / "scala"
KebsTestCompat / internalDependencyClasspath := Seq.empty
Test / internalDependencyClasspath += Attributed.blank((KebsTestCompat / classDirectory).value)
Test / compile := (Test / compile).dependsOn(KebsTestCompat / compile).value

Test / scalafmtOnCompile := true
ThisBuild / scalafmtOnCompile := true

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := "4.17.0"
ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / Compile / run / fork := true
classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat

enablePlugins(BaklavaSbtPlugin)
inConfig(Test)(
  BaklavaSbtPlugin.settings(Test) ++ Seq(
    baklavaTestClassPackage := "pl.iterators.epoca",
    baklavaFormatters := Seq(BaklavaSbtPlugin.model.Formatters.SimpleDocsFormatter, BaklavaSbtPlugin.model.Formatters.OpenApiFormatter)
  )
)

Test / baklavaGenerate := {
  val _          = (Test / compile).value
  val output     = (Test / baklavaOutputDir).value
  val classpath  = (Test / fullClasspath).value.files
  val taskRunner = (Test / run / runner).value
  val log        = streams.value.log
  val generatorArguments =
    Seq((Test / baklavaTestClassPackage).value, output, (Test / baklavaFetcher).value.toString) ++ (Test / baklavaFormatters).value.map(_.toString)

  taskRunner.run("pl.iterators.baklava.generator.Main", classpath, generatorArguments, log).get
  taskRunner
    .run("pl.iterators.epoca.utils.OpenApiContractPostProcessor", classpath, Seq((file(output) / "openapi" / "openapi.yml").getAbsolutePath), log)
    .get
}

lazy val verifyAll = taskKey[Unit]("Performs all verifications to assure that the build will pass CI checks.")
Test / verifyAll := {
  Def
    .sequential(
      Compile / scalafmtSbtCheck,
      Compile / scalafmtCheckAll,
      oauthE2e / scalafmtCheckAll,
      Compile / compile,
      Test / test,
      oauthE2e / Test / test,
      Test / baklavaGenerate
    )
    .value
}

BackendVerification.settings

lazy val root = project.in(file("."))
Compile / mainClass := Some("pl.iterators.epoca.main.Main")
lazy val oauthE2e = project
  .in(file("oauth-e2e"))
  .enablePlugins(JavaServerAppPackaging)
  .dependsOn(root % "compile->compile;test->test")
  .settings(
    name := "epoca-oauth-e2e",
    organization := (root / organization).value,
    version := (root / version).value,
    scalaVersion := (root / scalaVersion).value,
    scalacOptions := (root / scalacOptions).value,
    Test / scalacOptions := (root / Test / scalacOptions).value,
    libraryDependencies := (root / libraryDependencies).value,
    Test / parallelExecution := false,
    Test / internalDependencyClasspath += Attributed.blank((root / KebsTestCompat / classDirectory).value),
    Compile / mainClass := Some("pl.iterators.epoca.oauth.fixture.OAuthE2EMain"),
    packageName := "epoca-oauth-e2e",
    dockerBaseImage := (root / dockerBaseImage).value,
    Docker / stage := (Docker / stage).dependsOn(root / requireRuntimeImage).value,
    dockerRepository := (root / dockerRepository).value,
    Docker / daemonUser := "epoca-e2e",
    Docker / daemonGroup := "epoca-e2e",
    Docker / daemonUserUid := Some("1001"),
    Docker / daemonGroupGid := Some("1001"),
    dockerUpdateLatest := false,
    classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )
