package pl.iterators.epoca.oauth.services

import pl.iterators.epoca.oauth.services.OAuthConfig.OAuthTrustedProxyCidr

import java.net.InetAddress
import scala.util.Try

final class TrustedClientIpService(trustedProxyCidrs: Vector[OAuthTrustedProxyCidr]) {
  def resolve(socketPeer: InetAddress, forwardedFor: Seq[String]): InetAddress =
    if (!isTrusted(socketPeer)) socketPeer
    else {
      val parsed = forwardedFor.toVector.map(parseLiteral)
      if (parsed.isEmpty || parsed.exists(_.isEmpty)) socketPeer
      else parsed.flatten.reverse.dropWhile(isTrusted).headOption.getOrElse(parsed.flatten.head)
    }

  private def isTrusted(address: InetAddress): Boolean = trustedProxyCidrs.exists(_.contains(address))

  private def parseLiteral(value: String): Option[InetAddress] = {
    val candidate = value.trim
    if (candidate.contains(":"))
      Option
        .when(candidate.nonEmpty && candidate.forall(character => character == ':' || character == '.' || Character.digit(character, 16) >= 0))(
          candidate
        )
        .flatMap(raw => Try(InetAddress.getByName(raw)).toOption.filter(_.getAddress.length == 16))
    else {
      val octets = candidate.split("\\.", -1).toVector
      val parsed =
        octets.map(octet => Try(octet.toInt).toOption.filter(number => octet.nonEmpty && octet.forall(_.isDigit) && number >= 0 && number <= 255))
      Option.when(octets.size == 4 && parsed.forall(_.nonEmpty))(InetAddress.getByAddress(parsed.flatten.map(_.toByte).toArray))
    }
  }
}
