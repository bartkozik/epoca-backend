package pl.iterators.epoca.storage

import com.typesafe.config.{Config => TsConfig}

import scala.util.Try

/** Chooses the [[FileStorage]] implementation from `assets.storage` (`memory` | `s3`). */
object FileStorageFactory {

  def apply(config: TsConfig): FileStorage = {
    val assets = config.getConfig("assets")
    assets.getString("storage").toLowerCase match {
      case "memory" =>
        require(
          !(config.hasPath("user.avatarSync.enabled") && config.getBoolean("user.avatarSync.enabled")),
          "user.avatarSync.enabled = true requires assets.storage = s3; the memory storage does not persist anything"
        )
        new InMemoryFileStorage(assets.getString("memory.publicBaseUrl"))
      case "s3"  => new S3FileStorage(s3Config(assets.getConfig("s3")))
      case other => throw new IllegalArgumentException(s"Unknown assets.storage '$other', expected 'memory' or 's3'")
    }
  }

  private def optionalString(config: TsConfig, path: String): Option[String] =
    Try(config.getString(path)).toOption.map(_.trim).filter(_.nonEmpty)

  private[storage] def s3Config(s3: TsConfig): S3FileStorage.Config = {
    val bucket = optionalString(s3, "bucket").getOrElse(throw new IllegalArgumentException("assets.s3.bucket must be set when assets.storage = s3"))
    S3FileStorage.Config(
      bucket = bucket,
      region = s3.getString("region"),
      endpoint = optionalString(s3, "endpoint"),
      pathStyle = s3.getBoolean("pathStyle"),
      cdnDomain = optionalString(s3, "cdnDomain"),
      credentials = for {
        id     <- optionalString(s3, "accessKeyId")
        secret <- optionalString(s3, "secretAccessKey")
      } yield (id, secret)
    )
  }
}
