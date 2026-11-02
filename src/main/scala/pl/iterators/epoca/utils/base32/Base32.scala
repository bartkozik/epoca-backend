package pl.iterators.epoca.utils.base32

import scala.collection.mutable.ArrayBuffer

object Base32 {
  /*
   * Reference: https://datatracker.ietf.org/doc/html/rfc4648
   */
  private val BASE32_CHARS: Array[Char] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray
  private val BITS_LOOKUP: Array[Char] = {
    val array: Array[Char] = new Array[Char](128)
    BASE32_CHARS.indices.foreach { i =>
      array.update(BASE32_CHARS(i).toInt, i.toChar)
    }
    array
  }

  def encode(input: String): String = {
    val encoded      = new StringBuilder
    var buffer       = 0
    var bufferLength = 0

    for (b <- input.getBytes) {
      buffer <<= 8
      buffer |= b & 0xff
      bufferLength += 8

      while (bufferLength >= 5) {
        val index = (buffer >> (bufferLength - 5)) & 0x1f
        encoded.append(BASE32_CHARS(index))
        bufferLength -= 5

      }
    }

    while (encoded.length % 8 != 0) encoded.append('=')
    encoded.toString
  }

  def decode(base32: String): String = {
    val charArray    = base32.toUpperCase.replaceAll("=", "").toCharArray
    val decoded      = ArrayBuffer.empty[Byte]
    var buffer       = 0
    var bufferLength = 0
    for (c <- charArray) {
      buffer <<= 5
      buffer |= BITS_LOOKUP(c.toInt)
      bufferLength += 5
      while (bufferLength >= 8) {
        val b = (buffer >> (bufferLength - 8)).toByte
        decoded.append(b)
        bufferLength -= 8
      }
    }
    new String(decoded.toArray)
  }

}
