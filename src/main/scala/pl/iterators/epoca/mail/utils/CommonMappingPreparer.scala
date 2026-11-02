package pl.iterators.epoca.mail.utils

import io.circe._
import pl.iterators.epoca.mail.services.SendgridMailer

class CommonMappingPreparer(config: SendgridMailer.Config) {

  def prepare(): JsonObject =
    JsonObject("appUrl" -> Json.fromString(config.appUrl), "subjectPrefix" -> Json.fromString(config.subjectPrefix))
}
