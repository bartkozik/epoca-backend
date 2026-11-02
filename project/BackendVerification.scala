import sbt.Keys._
import sbt._

object BackendVerification {
  val backendVerificationDirectory   = settingKey[File]("backend verification evidence output directory")
  val writeBackendDependencyManifest = taskKey[Seq[File]]("Write deterministic backend verification dependency manifests")
  val writeBackendToolchainManifest  = taskKey[File]("Write deterministic backend verification toolchain manifest")

  private def write(
    file: File,
    header: String,
    rows: Seq[String]
  ): File = {
    IO.createDirectory(file.getParentFile)
    IO.writeLines(file, header +: rows.distinct.sorted)
    file
  }

  val settings: Seq[Setting[_]] = Seq(
    backendVerificationDirectory := target.value / "backend-verification",
    writeBackendDependencyManifest := {
      val output = backendVerificationDirectory.value / "dependencies"
      val declared = libraryDependencies.value.map { module =>
        Seq(module.configurations.getOrElse("compile"), module.organization, module.name, module.revision).mkString("\t")
      }
      val reports = update.value.configurations.flatMap(configuration =>
        configuration.modules.map(module =>
          Seq(configuration.configuration, module.module.organization, module.module.name, module.module.revision, module.evicted.toString)
            .mkString("\t")
        )
      )
      val declaredFile = write(output / "declared.tsv", "configuration\torganization\tname\trevision", declared)
      val resolvedFile = write(output / "resolved.tsv", "configuration\torganization\tname\trevision\tevicted", reports)
      Seq(declaredFile, resolvedFile)
    },
    writeBackendToolchainManifest := {
      val output = backendVerificationDirectory.value / "toolchain.tsv"
      write(
        output,
        "key\tvalue",
        Seq(
          s"scala\t${scalaVersion.value}",
          s"scalaBinary\t${scalaBinaryVersion.value}",
          s"javaRuntime\t${sys.props("java.runtime.version")}",
          s"javaVendor\t${sys.props("java.vendor")}",
          s"javaSpecification\t${sys.props("java.specification.version")}",
          s"sbt\t${sbtVersion.value}"
        )
      )
    }
  )
}
