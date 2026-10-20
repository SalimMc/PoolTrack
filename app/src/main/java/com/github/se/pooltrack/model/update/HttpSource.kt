package com.github.se.pooltrack.model.update

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_REDIRECTS = 5

/** An open HTTP response. [contentLength] is -1 when the server did not send one. */
class HttpResponse(val body: InputStream, val contentLength: Long) : Closeable {
  override fun close() = body.close()
}

/** Opens HTTP GET requests. A seam so the updater can be tested without a network. */
fun interface HttpSource {
  /** Throws an [IOException] if the request fails or the status is not 2xx. */
  fun open(url: String): HttpResponse
}

/**
 * Whether [url] is somewhere the updater may fetch from: HTTPS, on GitHub or the host GitHub serves
 * release files from. Redirects are checked against this too, so a response can't send the download
 * anywhere else.
 */
fun isTrustedGithubUrl(url: URL): Boolean {
  val host = url.host.lowercase()
  return url.protocol == "https" &&
      (host == "github.com" ||
          host.endsWith(".github.com") ||
          host == "githubusercontent.com" ||
          host.endsWith(".githubusercontent.com"))
}

/**
 * The [HttpSource] used in the app, backed by [HttpURLConnection]. It follows redirects itself, at
 * most [MAX_REDIRECTS] of them, and refuses the first URL and every redirect target that
 * [isTrusted] rejects (by default: only GitHub over HTTPS).
 */
class UrlConnectionSource(private val isTrusted: (URL) -> Boolean = ::isTrustedGithubUrl) :
    HttpSource {

  override fun open(url: String): HttpResponse {
    var target = URL(url)
    repeat(MAX_REDIRECTS + 1) {
      if (!isTrusted(target)) throw IOException("Untrusted address: $target")
      val connection = target.openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      connection.setRequestProperty("Accept", "application/vnd.github+json")
      connection.setRequestProperty("User-Agent", "PoolTrack")
      val status = connection.responseCode
      when (status) {
        in 200..299 -> return HttpResponse(connection.inputStream, connection.contentLengthLong)
        in 300..399 -> {
          val location = connection.getHeaderField("Location")
          connection.disconnect()
          target =
              location?.let { runCatching { URL(target, it) }.getOrNull() }
                  ?: throw IOException("Bad redirect from $target")
        }
        else -> {
          connection.disconnect()
          throw IOException("HTTP $status for $target")
        }
      }
    }
    throw IOException("Too many redirects for $url")
  }
}

/**
 * Reads the whole stream as text, but fails with an [IOException] once it holds more than
 * [maxBytes] bytes, so a hostile or broken server can't make the app read without end.
 */
fun InputStream.readTextLimited(maxBytes: Long): String {
  val buffer = java.io.ByteArrayOutputStream()
  val chunk = ByteArray(8 * 1024)
  var total = 0L
  while (true) {
    val read = read(chunk)
    if (read < 0) break
    total += read
    if (total > maxBytes) throw IOException("Response larger than $maxBytes bytes")
    buffer.write(chunk, 0, read)
  }
  return buffer.toString(Charsets.UTF_8.name())
}
