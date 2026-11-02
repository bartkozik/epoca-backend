package pl.iterators.epoca.auth.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.{FirebaseApp, FirebaseOptions}
import com.typesafe.config.{Config => TsConfig}

import java.io.ByteArrayInputStream
import scala.util.Try

class FirebaseAuthProvider(config: FirebaseAuthProvider.Config) {
  lazy val firebaseAuth: Option[FirebaseAuth] =
    config.key.flatMap { key =>
      Try {
        val serviceAccount = new ByteArrayInputStream(key.getBytes())
        val options = FirebaseOptions
          .builder()
          .setCredentials(GoogleCredentials.fromStream(serviceAccount))
          .build()

        val firebase = FirebaseApp.initializeApp(options)
        FirebaseAuth.getInstance(firebase)
      }.toOption
    }
}

object FirebaseAuthProvider {

  case class Config(key: Option[String])

  object Config {
    def apply(config: TsConfig): FirebaseAuthProvider.Config =
      FirebaseAuthProvider.Config(Try(config.getString("firebase.key")).toOption)
  }
}
