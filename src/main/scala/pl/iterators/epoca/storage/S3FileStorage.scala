package pl.iterators.epoca.storage

import cats.effect.IO
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{DeleteObjectRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** S3 compatible storage (AWS S3, MinIO, "silo", ...). Set `endpoint` for non-AWS services, usually together with `pathStyle`. */
class S3FileStorage(config: S3FileStorage.Config) extends FileStorage with AutoCloseable {

  private val clientCreated = new AtomicBoolean(false)

  private lazy val client: S3Client = {
    clientCreated.set(true)
    val builder = S3Client
      .builder()
      .region(Region.of(config.region))
      .overrideConfiguration(
        ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5)).build()
      )
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(config.pathStyle).build())
      .credentialsProvider(
        config.credentials.fold[software.amazon.awssdk.auth.credentials.AwsCredentialsProvider](DefaultCredentialsProvider.create()) {
          case (accessKeyId, secretAccessKey) => StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))
        }
      )
    config.endpoint.foreach(endpoint => builder.endpointOverride(URI.create(endpoint)))
    builder.build()
  }

  override def put(
    key: String,
    bytes: Array[Byte],
    contentType: String
  ): IO[Unit] =
    IO.blocking {
      client.putObject(
        PutObjectRequest
          .builder()
          .bucket(config.bucket)
          .key(key)
          .contentType(contentType)
          .cacheControl("public, max-age=31536000, immutable")
          .build(),
        RequestBody.fromBytes(bytes)
      )
    }.void

  override def delete(key: String): IO[Unit] =
    IO.blocking {
      client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
    }.void

  override def publicUrl(key: String): String = S3FileStorage.publicUrl(config, key)

  /** Releases the HTTP connections; a client that was never used is not created just to be closed. */
  override def close(): Unit = if (clientCreated.get) client.close()
}

object S3FileStorage {
  final case class Config(
    bucket: String,
    region: String,
    endpoint: Option[String],
    pathStyle: Boolean,
    cdnDomain: Option[String],
    credentials: Option[(String, String)])

  /** Accepts a CDN domain with or without scheme (https is assumed, an explicit http:// is preserved) and with or without trailing slash. */
  private[storage] def cdnBaseUrl(cdnDomain: String): String = {
    val trimmed = cdnDomain.trim.stripSuffix("/")
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else s"https://$trimmed"
  }

  private[storage] def publicUrl(config: Config, key: String): String =
    config.cdnDomain match {
      case Some(cdn) => s"${cdnBaseUrl(cdn)}/$key"
      case None =>
        config.endpoint match {
          case Some(endpoint) =>
            val uri  = URI.create(endpoint)
            val base = endpoint.stripSuffix("/")
            if (config.pathStyle || config.bucket.contains('.')) s"$base/${config.bucket}/$key" // dotted names break TLS wildcard certificates
            else s"${uri.getScheme}://${config.bucket}.${uri.getAuthority}/$key"
          case None if config.bucket.contains('.') => s"https://s3.${config.region}.amazonaws.com/${config.bucket}/$key"
          case None                                => s"https://${config.bucket}.s3.${config.region}.amazonaws.com/$key"
        }
    }
}
